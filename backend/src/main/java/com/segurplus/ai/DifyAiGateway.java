package com.segurplus.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.segurplus.documents.Kb;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Adaptador Dify 1.18 (ADR 0003). Verificado contra el código de la instancia y el Swagger (ver ADR, «Verificación»):
 * Knowledge API {@code /v1/datasets/...} (create-by-text, metadata, retrieve) y apps de chat {@code /v1/chat-messages}.
 * <p>Seguridad: este adaptador NO decide permisos. Las claves viajan solo en la cabecera {@code Authorization} y no se
 * registran; los mensajes de error llevan el código HTTP, nunca el cuerpo (podría eco de contenido).
 * Reintento: un solo reintento, solo antes del primer token, contra la aplicación de reserva (otro modelo) si existe.
 */
@Component
class DifyAiGateway implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(DifyAiGateway.class);
    private static final List<String> METADATA_FIELDS = List.of("doc_id", "kb", "classification", "allowed_roles", "owner_id");
    private static final Duration BLOCKING_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INDEX_TIMEOUT = Duration.ofSeconds(90);

    private final AiProperties.Dify cfg;
    private final ObjectMapper mapper;
    private final WebClient client;
    private final Map<Kb, String> datasetIds = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> metadataFieldIds = new ConcurrentHashMap<>();

    DifyAiGateway(AiProperties props, ObjectMapper mapper) {
        this.cfg = props.dify();
        this.mapper = mapper;
        HttpClient http = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) cfg.connectTimeout().toMillis());
        this.client = WebClient.builder()
                .baseUrl(cfg.baseUrl() == null || cfg.baseUrl().isBlank() ? "http://localhost:1" : stripSlash(cfg.baseUrl()))
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Override
    public boolean configured() {
        return cfg.configured();
    }

    // ---------------------------------------------------------------- planificación y generación (apps de chat)

    @Override
    public Completion plan(String prompt, String difyUser) {
        requireConfigured();
        AiException last = null;
        for (String key : keys(cfg.plannerApiKey(), cfg.plannerApiKeyFallback())) {
            try {
                ObjectNode body = chatBody(prompt, difyUser, "blocking");
                JsonNode res = post("/v1/chat-messages", key, body, BLOCKING_TIMEOUT);
                return new Completion(res.path("answer").asText(""), usage(res.path("metadata").path("usage")));
            } catch (AiException e) {
                last = e;
                log.warn("Dify plan call failed ({}); {}", e.kind(), "trying fallback if any");
            }
        }
        throw last == null ? new AiException(AiException.Kind.NOT_CONFIGURED, "No planner key") : last;
    }

    @Override
    public Usage generate(String prompt, String difyUser, Consumer<String> onText) {
        requireConfigured();
        AiException last = null;
        for (String key : keys(cfg.appApiKey(), cfg.appApiKeyFallback())) {
            AtomicBoolean started = new AtomicBoolean(false);
            try {
                return streamOnce(key, prompt, difyUser, onText, started);
            } catch (AiException e) {
                last = e.started(started.get());
                if (started.get()) {
                    throw last; // ya hay texto en el cliente: sin reintento (ADR 0003 §12)
                }
                log.warn("Dify generation failed before first token ({}); {}", e.kind(), "trying fallback if any");
            }
        }
        throw last == null ? new AiException(AiException.Kind.NOT_CONFIGURED, "No app key") : last;
    }

    private Usage streamOnce(String key, String prompt, String difyUser, Consumer<String> onText, AtomicBoolean started) {
        ObjectNode body = chatBody(prompt, difyUser, "streaming");
        Usage[] usage = {Usage.NONE};
        AtomicBoolean ended = new AtomicBoolean(false);
        try {
            Flux<ServerSentEvent<String>> events = client.post().uri("/v1/chat-messages")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key).contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(body.toString())
                    .exchangeToFlux(res -> {
                        if (res.statusCode().isError()) {
                            return res.releaseBody().thenMany(Flux.error(statusError(res)));
                        }
                        return res.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { });
                    });
            events.filter(e -> e.data() != null && !e.data().isBlank())
                    .map(e -> parse(e.data()))
                    .filter(n -> !"ping".equals(n.path("event").asText()))
                    // primera respuesta ≤ 10 s; después, inactividad ≤ 30 s (los ping no cuentan como actividad)
                    .timeout(Mono.delay(cfg.firstResponseTimeout()), n -> Mono.delay(cfg.inactivityTimeout()))
                    .doOnNext(n -> {
                        switch (n.path("event").asText()) {
                            case "message", "agent_message" -> {
                                String text = n.path("answer").asText("");
                                if (!text.isEmpty()) {
                                    started.set(true);
                                    onText.accept(text);
                                }
                            }
                            case "message_end" -> {
                                usage[0] = usage(n.path("metadata").path("usage"));
                                ended.set(true);
                            }
                            case "error" -> throw new AiException(AiException.Kind.UNAVAILABLE,
                                    "Dify stream error status=" + n.path("status").asInt(0), started.get(), null);
                            default -> { }
                        }
                    })
                    .blockLast(cfg.totalTimeout());
        } catch (AiException | ClientDisconnected e) {
            throw e;
        } catch (IllegalStateException e) { // blockLast: tope total superado
            throw new AiException(AiException.Kind.TIMEOUT, "Dify total timeout", started.get(), e);
        } catch (RuntimeException e) {
            throw translate(e, started.get());
        }
        if (!ended.get()) {
            throw new AiException(AiException.Kind.UNAVAILABLE, "Dify stream ended without message_end", started.get(), null);
        }
        return usage[0];
    }

    private ObjectNode chatBody(String prompt, String difyUser, String mode) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("inputs");
        body.put("query", prompt);
        body.put("response_mode", mode);
        body.put("user", difyUser);
        return body;
    }

    // ---------------------------------------------------------------- Knowledge API

    @Override
    public List<Fragment> retrieve(Kb kb, String query, RetrievalFilter filter, int topK) {
        requireConfigured();
        String ds = datasetId(kb);
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query.length() > 250 ? query.substring(0, 250) : query); // límite de Dify: 250
        ObjectNode rm = body.putObject("retrieval_model");
        rm.put("search_method", "semantic_search");
        rm.put("reranking_enable", false);
        rm.put("top_k", topK);
        rm.put("score_threshold_enabled", false);
        if (filter != null && !filter.isEmpty()) {
            ObjectNode mfc = rm.putObject("metadata_filtering_conditions");
            mfc.put("logical_operator", filter.logicalOperator());
            ArrayNode conds = mfc.putArray("conditions");
            for (var c : filter.conditions()) {
                conds.addObject().put("name", c.name()).put("comparison_operator", c.operator()).put("value", c.value());
            }
        }
        JsonNode res = post("/v1/datasets/" + ds + "/retrieve", cfg.knowledgeApiKey(), body, BLOCKING_TIMEOUT);
        List<Fragment> out = new ArrayList<>();
        for (JsonNode rec : res.path("records")) {
            JsonNode seg = rec.path("segment");
            JsonNode doc = seg.path("document");
            out.add(new Fragment(doc.path("id").asText(null), metadataValue(doc.path("doc_metadata"), "doc_id"),
                    seg.path("id").asText(null), seg.path("content").asText(""), rec.path("score").asDouble(0)));
        }
        return out;
    }

    @Override
    public IndexedRef indexDocument(Kb kb, String name, String text, DocMetadata metadata) {
        requireConfigured();
        String ds = datasetId(kb);
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        body.put("text", text);
        body.put("indexing_technique", "high_quality");
        body.put("doc_form", "text_model");
        body.put("doc_language", "Spanish");
        ObjectNode rule = body.putObject("process_rule");
        rule.put("mode", "custom");
        ObjectNode rules = rule.putObject("rules");
        rules.putArray("pre_processing_rules").addObject().put("id", "remove_extra_spaces").put("enabled", false);
        ObjectNode seg = rules.putObject("segmentation");
        seg.put("separator", "\n\n");
        seg.put("max_tokens", 700);
        seg.put("chunk_overlap", 70);
        JsonNode res = post("/v1/datasets/" + ds + "/document/create-by-text", cfg.knowledgeApiKey(), body, INDEX_TIMEOUT);
        String docId = res.path("document").path("id").asText(null);
        if (docId == null) {
            throw new AiException(AiException.Kind.UNAVAILABLE, "Dify create-by-text returned no document id");
        }
        updateMetadata(kb, docId, metadata);
        return new IndexedRef(ds, docId);
    }

    @Override
    public void updateMetadata(Kb kb, String difyDocumentId, DocMetadata md) {
        requireConfigured();
        String ds = datasetId(kb);
        Map<String, String> fields = ensureMetadataFields(ds);
        ObjectNode body = mapper.createObjectNode();
        ObjectNode op = body.putArray("operation_data").addObject();
        op.put("document_id", difyDocumentId);
        ArrayNode list = op.putArray("metadata_list");
        addMeta(list, fields, "doc_id", md.docId());
        addMeta(list, fields, "kb", md.kb());
        addMeta(list, fields, "classification", md.classification());
        addMeta(list, fields, "allowed_roles", md.allowedRoles());
        addMeta(list, fields, "owner_id", md.ownerId() == null ? "-" : md.ownerId());
        post("/v1/datasets/" + ds + "/documents/metadata", cfg.knowledgeApiKey(), body, BLOCKING_TIMEOUT);
    }

    @Override
    public void deleteDocument(Kb kb, String difyDocumentId) {
        requireConfigured();
        String ds = datasetId(kb);
        try {
            client.delete().uri("/v1/datasets/{ds}/documents/{doc}", ds, difyDocumentId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.knowledgeApiKey())
                    .retrieve().toBodilessEntity().block(BLOCKING_TIMEOUT);
        } catch (WebClientResponseException.NotFound e) {
            // ya no existe: idempotente
        } catch (RuntimeException e) {
            throw translate(e, false);
        }
    }

    @Override
    public IndexingState indexingStatus(Kb kb, String difyDocumentId) {
        requireConfigured();
        String ds = datasetId(kb);
        JsonNode res = get("/v1/datasets/" + ds + "/documents/" + difyDocumentId, cfg.knowledgeApiKey());
        return switch (res.path("indexing_status").asText("")) {
            case "completed" -> IndexingState.COMPLETED;
            case "error" -> IndexingState.ERROR;
            case "waiting" -> IndexingState.WAITING;
            case "parsing", "cleaning", "splitting", "indexing" -> IndexingState.INDEXING;
            default -> IndexingState.UNKNOWN;
        };
    }

    // ---------------------------------------------------------------- datasets y metadatos

    private String datasetId(Kb kb) {
        return datasetIds.computeIfAbsent(kb, k -> {
            String wanted = cfg.datasetPrefix() + switch (k) {
                case GENERAL -> "General";
                case DIRECCION -> "Direccion";
                case FINANZAS -> "Finanzas";
                case EMPLEADOS -> "Empleados";
            };
            JsonNode res = get("/v1/datasets?page=1&limit=100", cfg.knowledgeApiKey());
            for (JsonNode d : res.path("data")) {
                if (wanted.equals(d.path("name").asText())) {
                    return d.path("id").asText();
                }
            }
            throw new AiException(AiException.Kind.NOT_CONFIGURED, "Dify dataset for KB " + k + " does not exist");
        });
    }

    /** Crea (una vez por dataset) los campos de metadatos y devuelve nombre → id. */
    private Map<String, String> ensureMetadataFields(String ds) {
        Map<String, String> cached = metadataFieldIds.get(ds);
        if (cached != null) {
            return cached;
        }
        JsonNode res = get("/v1/datasets/" + ds + "/metadata", cfg.knowledgeApiKey());
        Map<String, String> ids = new ConcurrentHashMap<>();
        for (JsonNode f : res.path("doc_metadata")) {
            ids.put(f.path("name").asText(), f.path("id").asText());
        }
        for (String name : METADATA_FIELDS) {
            if (!ids.containsKey(name)) {
                ObjectNode body = mapper.createObjectNode().put("type", "string").put("name", name);
                JsonNode created = post("/v1/datasets/" + ds + "/metadata", cfg.knowledgeApiKey(), body, BLOCKING_TIMEOUT);
                ids.put(name, created.path("id").asText());
            }
        }
        metadataFieldIds.put(ds, ids);
        return ids;
    }

    private static void addMeta(ArrayNode list, Map<String, String> fields, String name, String value) {
        list.addObject().put("id", fields.get(name)).put("name", name).put("value", value);
    }

    private static String metadataValue(JsonNode docMetadata, String name) {
        if (docMetadata.isObject()) {
            JsonNode v = docMetadata.get(name);
            return v == null || v.isNull() ? null : v.asText();
        }
        if (docMetadata.isArray()) {
            for (JsonNode m : docMetadata) {
                if (name.equals(m.path("name").asText())) {
                    return m.path("value").isNull() ? null : m.path("value").asText();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- HTTP

    private JsonNode post(String uri, String key, ObjectNode body, Duration timeout) {
        try {
            String raw = client.post().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body.toString())
                    .exchangeToMono(res -> res.statusCode().isError()
                            ? res.releaseBody().then(Mono.error(statusError(res))) : res.bodyToMono(String.class))
                    .timeout(timeout).block();
            return parse(raw);
        } catch (AiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate(e, false);
        }
    }

    private JsonNode get(String uri, String key) {
        try {
            String raw = client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .exchangeToMono(res -> res.statusCode().isError()
                            ? res.releaseBody().then(Mono.error(statusError(res))) : res.bodyToMono(String.class))
                    .timeout(BLOCKING_TIMEOUT).block();
            return parse(raw);
        } catch (AiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate(e, false);
        }
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw == null ? "{}" : raw);
        } catch (Exception e) {
            throw new AiException(AiException.Kind.UNAVAILABLE, "Dify returned a malformed response", false, null);
        }
    }

    private static AiException statusError(ClientResponse res) {
        int status = res.statusCode().value();
        AiException.Kind kind = status == 429 || status >= 500 ? AiException.Kind.UNAVAILABLE : AiException.Kind.REJECTED;
        return new AiException(kind, "Dify HTTP " + status);
    }

    private static AiException translate(RuntimeException e, boolean started) {
        if (e instanceof ClientDisconnected cd) {
            throw cd;
        }
        if (e instanceof AiException ai) {
            return ai;
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof java.util.concurrent.TimeoutException) {
            return new AiException(AiException.Kind.TIMEOUT, "Dify timeout", started, e);
        }
        if (e instanceof WebClientRequestException || root instanceof java.net.ConnectException) {
            return new AiException(AiException.Kind.UNAVAILABLE, "Dify unreachable", started, e);
        }
        return new AiException(AiException.Kind.UNAVAILABLE, "Dify error " + e.getClass().getSimpleName(), started, e);
    }

    private static Usage usage(JsonNode u) {
        return new Usage(u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0));
    }

    private static List<String> keys(String primary, String fallback) {
        List<String> keys = new ArrayList<>();
        if (primary != null && !primary.isBlank()) {
            keys.add(primary);
        }
        if (fallback != null && !fallback.isBlank()) {
            keys.add(fallback);
        } else if (!keys.isEmpty()) {
            keys.add(primary); // sin reserva: un reintento con la misma aplicación
        }
        return keys;
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new AiException(AiException.Kind.NOT_CONFIGURED, "Dify is not configured");
        }
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
