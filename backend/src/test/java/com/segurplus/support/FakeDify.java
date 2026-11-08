package com.segurplus.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import java.util.Map;

/**
 * Dify SIMULADO (WireMock) con la forma de la API 1.18 verificada en la instancia real: Knowledge API (datasets, metadatos,
 * create-by-text, estado, retrieve) y apps de chat (planificación en blocking, generación en streaming). Sirve para probar el
 * indexador, la re-autorización de fragmentos y el chat sin red ni Gemini. NO sustituye la corrida real (evaluation/).
 * El retrieve simulado ignora a propósito cualquier filtro de metadatos: el peor caso (Dify devuelve el corpus entero).
 */
public final class FakeDify {

    public static final String KNOWLEDGE_KEY = "test-knowledge-key";
    public static final String APP_KEY = "test-app-key";
    public static final String PLANNER_KEY = "test-planner-key";
    public static final Map<String, String> DATASETS = Map.of("GENERAL", "ds-general", "DIRECCION", "ds-direccion",
            "FINANZAS", "ds-finanzas", "EMPLEADOS", "ds-empleados");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final WireMockServer SERVER = new WireMockServer(options().dynamicPort().globalTemplating(true));

    static {
        SERVER.start();
    }

    private FakeDify() {
    }

    public static String baseUrl() {
        return SERVER.baseUrl();
    }

    /** Limpia stubs y peticiones y deja la instancia «sana» (todo indexa bien, retrieve vacío). */
    public static void reset() {
        SERVER.resetAll();
        String auth = "Bearer " + KNOWLEDGE_KEY;
        ArrayNode list = MAPPER.createArrayNode();
        DATASETS.forEach((kb, id) -> list.addObject().put("id", id).put("name", "SegurPlus " + switch (kb) {
            case "GENERAL" -> "General";
            case "DIRECCION" -> "Direccion";
            case "FINANZAS" -> "Finanzas";
            default -> "Empleados";
        }));
        SERVER.stubFor(get(urlPathEqualTo("/v1/datasets")).withHeader("Authorization", equalTo(auth))
                .willReturn(json(MAPPER.createObjectNode().set("data", list).toString())));
        SERVER.stubFor(get(urlPathMatching("/v1/datasets/[^/]+/metadata")).willReturn(json("{\"doc_metadata\":[],\"built_in_field_enabled\":false}")));
        SERVER.stubFor(post(urlPathMatching("/v1/datasets/[^/]+/metadata")).willReturn(json("{\"id\":\"{{randomValue type='UUID'}}\",\"type\":\"string\",\"name\":\"x\"}")
                .withTransformers("response-template")));
        SERVER.stubFor(post(urlPathMatching("/v1/datasets/[^/]+/documents/metadata")).willReturn(json("{\"result\":\"success\"}")));
        SERVER.stubFor(post(urlPathMatching("/v1/datasets/[^/]+/document/create-by-text"))
                .willReturn(json("{\"document\":{\"id\":\"{{randomValue type='UUID'}}\"},\"batch\":\"b1\"}").withTransformers("response-template")));
        SERVER.stubFor(get(urlPathMatching("/v1/datasets/[^/]+/documents/[^/]+")).willReturn(json("{\"indexing_status\":\"completed\"}")));
        SERVER.stubFor(delete(urlPathMatching("/v1/datasets/[^/]+/documents/[^/]+")).willReturn(aResponse().withStatus(204)));
        DATASETS.values().forEach(ds -> stubRetrieve(ds, List.of()));
        stubPlan("{\"retrieve\":{\"query\":\"pregunta\"},\"tools\":[]}");
        stubGeneration("Respuesta de prueba.", 100, 20);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    /** Fragmento simulado: id del documento en Dify, metadato doc_id (documents.id), contenido y score. */
    public record Frag(String difyDocumentId, String metadataDocId, String segmentId, String content, double score) {
    }

    public static void stubRetrieve(String datasetId, List<Frag> fragments) {
        ArrayNode records = MAPPER.createArrayNode();
        for (Frag f : fragments) {
            ObjectNode rec = records.addObject();
            ObjectNode seg = rec.putObject("segment");
            seg.put("id", f.segmentId()).put("content", f.content());
            ObjectNode doc = seg.putObject("document");
            doc.put("id", f.difyDocumentId());
            ObjectNode md = doc.putObject("doc_metadata");
            if (f.metadataDocId() != null) {
                md.put("doc_id", f.metadataDocId());
            }
            rec.put("score", f.score());
        }
        SERVER.stubFor(post(urlPathEqualTo("/v1/datasets/" + datasetId + "/retrieve")).withHeader("Authorization", equalTo("Bearer " + KNOWLEDGE_KEY))
                .willReturn(json(MAPPER.createObjectNode().set("records", records).toString())));
    }

    public static void stubPlan(String planJson) {
        ObjectNode body = MAPPER.createObjectNode().put("answer", planJson);
        body.putObject("metadata").putObject("usage").put("prompt_tokens", 50).put("completion_tokens", 10);
        SERVER.stubFor(post(urlEqualTo("/v1/chat-messages")).withHeader("Authorization", equalTo("Bearer " + PLANNER_KEY))
                .willReturn(json(body.toString())));
    }

    /** Generación en streaming con el formato de eventos de Dify (message… message_end con usage). */
    public static void stubGeneration(String answer, int tokensIn, int tokensOut) {
        StringBuilder sse = new StringBuilder("data: {\"event\":\"ping\"}\n\n");
        for (String part : split(answer)) {
            sse.append("data: ").append(MAPPER.createObjectNode().put("event", "message").put("answer", part)).append("\n\n");
        }
        ObjectNode end = MAPPER.createObjectNode().put("event", "message_end");
        end.putObject("metadata").putObject("usage").put("prompt_tokens", tokensIn).put("completion_tokens", tokensOut);
        sse.append("data: ").append(end).append("\n\n");
        SERVER.stubFor(post(urlEqualTo("/v1/chat-messages")).withHeader("Authorization", equalTo("Bearer " + APP_KEY))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(sse.toString())));
    }

    /** Stream que emite texto y se corta sin message_end (conexión cerrada a mitad de respuesta). */
    public static void stubInterruptedGeneration(String partial) {
        String sse = "data: " + MAPPER.createObjectNode().put("event", "message").put("answer", partial) + "\n\n";
        SERVER.stubFor(post(urlEqualTo("/v1/chat-messages")).withHeader("Authorization", equalTo("Bearer " + APP_KEY))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(sse)));
    }

    public static void stubGenerationStatus(int status) {
        SERVER.stubFor(post(urlEqualTo("/v1/chat-messages")).withHeader("Authorization", equalTo("Bearer " + APP_KEY))
                .willReturn(aResponse().withStatus(status).withBody("{\"code\":\"x\"}")));
    }

    private static List<String> split(String s) {
        int mid = Math.max(1, s.length() / 2);
        return s.length() < 4 ? List.of(s) : List.of(s.substring(0, mid), s.substring(mid));
    }

    /** Cuerpos (JSON) de las peticiones a /v1/chat-messages hechas con la clave dada. */
    public static List<JsonNode> chatRequests(String key) {
        return SERVER.findAll(postRequestedFor(urlEqualTo("/v1/chat-messages")).withHeader("Authorization", equalTo("Bearer " + key))).stream()
                .map(r -> {
                    try {
                        return MAPPER.readTree(r.getBodyAsString());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    public static List<JsonNode> requestsTo(String urlRegex) {
        return SERVER.findAll(postRequestedFor(urlPathMatching(urlRegex))).stream().map(r -> {
            try {
                return MAPPER.readTree(r.getBodyAsString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

}
