package com.segurplus.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.ai.AiGateway.Usage;
import com.segurplus.ai.api.ChatRequest;
import com.segurplus.audit.AuditAction;
import com.segurplus.audit.AuditEvent;
import com.segurplus.audit.AuditPublisher;
import com.segurplus.auth.CurrentUserService;
import com.segurplus.auth.Subject;
import com.segurplus.documents.DocumentCitationService.Citable;
import com.segurplus.shared.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orquestación de un turno de chat (04-chat-flow). Identidad y alcance salen del {@link Subject} (JWT); el cliente solo aporta
 * {@code conversationId} y {@code message}. Orden fijo: planificar → herramientas tipadas (servicios de {@code data}) →
 * recuperar y RE-AUTORIZAR fragmentos → generar en streaming SOLO con contexto autorizado → verificar → persistir.
 * <p>Nada de contenido (mensajes, fragmentos, prompts, JWT) va a logs ni a auditoría: solo contadores y códigos.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String ERROR_UNAVAILABLE = "The assistant is temporarily unavailable. Please try again.";

    /** Destino de los eventos SSE. Debe lanzar {@link ClientDisconnected} si el cliente se fue (cancelación). */
    public interface EventSink {
        void send(String event, Object data);
    }

    /** Turno ya validado y persistido (mensaje de usuario y mensaje de asistente PENDING). */
    public record Turn(UUID conversationId, UUID assistantMessageId, Subject subject, String message, long startedNanos) {
    }

    private final ConversationService conversations;
    private final Planner planner;
    private final ToolExecutor tools;
    private final RetrievalService retrieval;
    private final AiGateway gateway;
    private final AiProperties props;
    private final AuditPublisher audit;
    private final CurrentUserService users;
    private final ObjectMapper mapper;
    private final Clock clock;

    ChatService(ConversationService conversations, Planner planner, ToolExecutor tools, RetrievalService retrieval, AiGateway gateway,
            AiProperties props, AuditPublisher audit, CurrentUserService users, ObjectMapper mapper, Clock clock) {
        this.conversations = conversations;
        this.planner = planner;
        this.tools = tools;
        this.retrieval = retrieval;
        this.gateway = gateway;
        this.props = props;
        this.audit = audit;
        this.users = users;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Parte síncrona (antes de abrir el SSE): validación, propiedad de la conversación (ajena ⇒ 404) y persistencia inicial. */
    public Turn prepare(Subject subject, ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw ApiException.badRequest("message is required.");
        }
        if (request.message().length() > props.chat().maxMessageChars()) {
            throw ApiException.badRequest("message exceeds the maximum length.");
        }
        if (profileOf(subject) == null) {
            throw ApiException.forbidden();
        }
        if (!gateway.configured()) {
            throw ApiException.serviceUnavailable(ERROR_UNAVAILABLE);
        }
        if (!request.ignoredIdentityFields().isEmpty()) {
            audit.publish(AuditEvent.rest(AuditAction.IGNORED_CLIENT_FIELD, subject.userId(), true, "CLIENT_IDENTITY_FIELD_IGNORED")
                    .withDetails(Map.of("fields", request.ignoredIdentityFields().stream().sorted().toList(), "path", "/api/v1/chat")));
        }
        long started = System.nanoTime();
        UUID conversationId;
        if (request.conversationId() != null) {
            conversations.requireOwned(subject, request.conversationId());
            conversationId = request.conversationId();
        } else {
            conversationId = conversations.create(subject, request.message());
        }
        conversations.addMessage(conversationId, "USER", request.message(), "COMPLETED");
        UUID assistantId = conversations.addMessage(conversationId, "ASSISTANT", "", "PENDING");
        return new Turn(conversationId, assistantId, subject, request.message(), started);
    }

    /** Parte asíncrona: nunca lanza; los fallos se comunican como evento {@code error} y quedan en {@code messages.status}. */
    public void run(Turn turn, EventSink sink) {
        Subject subject = turn.subject();
        AiProperties.Profile profile = profileOf(subject);
        StringBuilder answer = new StringBuilder();
        Usage usage = Usage.NONE;
        List<ToolResult> toolResults = new ArrayList<>();
        RetrievalService.Outcome found = new RetrievalService.Outcome(List.of(), 0, 0, 0);
        boolean documentsSearched = false;
        String status = "FAILED";
        String errorCode = null;
        int errorStatus = 503;
        ChatVerifier.Verification verification = null;
        try {
            sink.send("conversation", Map.of("conversationId", turn.conversationId(), "messageId", turn.assistantMessageId()));
            List<String[]> history = historyBefore(turn);
            String historyText = history.stream().map(t -> ("USER".equals(t[0]) ? "Usuario: " : "Asistente: ")
                    + PromptBuilder.clip(t[1], PromptBuilder.MAX_HISTORY_CHARS)).collect(Collectors.joining("\n"));

            sink.send("status", Map.of("phase", "planning"));
            Planner.Planned planned = planner.plan(subject, profile, turn.message(), historyText);
            usage = usage.plus(planned.usage());
            for (ToolCall call : planned.plan().tools()) {
                ToolResult result = tools.execute(subject, call);
                toolResults.add(result);
                sink.send("tool", Map.of("tool", result.tool(), "params", result.params()));
            }
            if (planned.plan().retrieveQuery() != null) {
                documentsSearched = true;
                sink.send("status", Map.of("phase", "retrieving"));
                try {
                    found = retrieval.retrieve(subject, profile, planned.plan().retrieveQuery());
                } catch (AiException e) {
                    if (toolResults.isEmpty()) {
                        throw e; // sin datos de ninguna clase no hay respuesta honesta posible
                    }
                    log.warn("Document retrieval unavailable ({}); answering with tool data only", e.kind());
                    documentsSearched = false;
                }
            }

            sink.send("status", Map.of("phase", "generating"));
            conversations.updateStatus(turn.assistantMessageId(), "STREAMING");
            String prompt = PromptBuilder.build(mapper, LocalDate.now(clock), history, toolResults, found.chunks(), documentsSearched, turn.message());
            usage = usage.plus(gateway.generate(prompt, users.difyUserRef(subject), text -> {
                answer.append(text);
                sink.send("delta", Map.of("text", text));
            }));

            List<Citable> docs = found.documents();
            Set<String> authorizedCodes = docs.stream().map(Citable::code).collect(Collectors.toSet());
            List<String> grounding = new ArrayList<>();
            toolResults.forEach(t -> grounding.add(PromptBuilder.json(mapper, t)));
            found.chunks().forEach(c -> grounding.add(c.content()));
            grounding.add(turn.message());
            history.forEach(t -> grounding.add(t[1]));
            grounding.add(LocalDate.now(clock).toString());
            verification = ChatVerifier.verify(answer.toString(), authorizedCodes, grounding);
            status = "COMPLETED";
        } catch (ClientDisconnected e) {
            status = "CANCELLED";
        } catch (AiException e) {
            errorCode = e.kind() == AiException.Kind.TIMEOUT ? "ai-timeout" : "ai-unavailable";
            errorStatus = e.kind() == AiException.Kind.TIMEOUT ? 504 : 503;
            log.warn("Chat turn failed ({})", e.kind());
        } catch (RuntimeException e) {
            errorCode = "ai-error";
            errorStatus = 500;
            log.warn("Chat turn failed ({})", e.getClass().getSimpleName());
        }

        long latencyMs = (System.nanoTime() - turn.startedNanos()) / 1_000_000;
        List<Citable> docs = found.documents();
        Set<String> cited = verification == null ? Set.of() : Set.copyOf(verification.citedCodes());
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Citable d : docs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("documentId", d.id());
            s.put("code", d.code());
            s.put("cited", cited.contains(d.code()));
            sources.add(s);
        }
        List<Map<String, Object>> toolsJson = toolResults.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tool", t.tool());
            m.put("params", t.params());
            m.put("rows", t.rows());
            m.put("totals", t.totals());
            return m;
        }).toList();
        Map<String, Object> verificationJson = verification == null ? null : verificationJson(verification);
        try {
            conversations.finishAssistantMessage(turn.assistantMessageId(), turn.conversationId(), answer.toString(), status, sources,
                    toolsJson, verificationJson, usage.tokensIn(), usage.tokensOut(), latencyMs);
        } catch (RuntimeException e) {
            log.warn("Could not persist the assistant message ({})", e.getClass().getSimpleName());
        }
        audit.publish(new AuditEvent(AuditAction.CHAT, subject.userId(), "CONVERSATION", turn.conversationId().toString(),
                "COMPLETED".equals(status), status, AuditEvent.Channel.REST,
                Map.of("tools", toolResults.size(), "fragmentsRetrieved", found.retrieved(), "fragmentsKept", found.chunks().size(),
                        "fragmentsDropped", found.dropped(), "latencyMs", latencyMs, "tokensIn", usage.tokensIn(), "tokensOut", usage.tokensOut())));

        try {
            if ("COMPLETED".equals(status)) {
                List<Map<String, Object>> sourceEvents = new ArrayList<>();
                for (Citable d : docs) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("documentId", d.id());
                    s.put("code", d.code());
                    s.put("title", d.title());
                    s.put("cited", cited.contains(d.code()));
                    sourceEvents.add(s);
                }
                sink.send("sources", Map.of("sources", sourceEvents));
                sink.send("verification", verificationJson);
                sink.send("done", Map.of("messageId", turn.assistantMessageId(), "tokensIn", usage.tokensIn(), "tokensOut", usage.tokensOut(),
                        "latencyMs", latencyMs));
            } else if (errorCode != null) {
                sink.send("error", Map.of("status", errorStatus, "code", errorCode, "detail", ERROR_UNAVAILABLE));
            }
        } catch (RuntimeException e) {
            // el cliente ya no está: nada más que hacer
        }
    }

    private static Map<String, Object> verificationJson(ChatVerifier.Verification v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", v.ok());
        m.put("citationsValid", v.citationsValid());
        m.put("citedCodes", v.citedCodes());
        m.put("unknownCitations", v.unknownCitations());
        m.put("numbersChecked", v.numbersChecked());
        m.put("unsupportedNumbers", v.unsupportedNumbers());
        return m;
    }

    private List<String[]> historyBefore(Turn turn) {
        List<String[]> all = conversations.recentTurns(turn.conversationId(), props.chat().historyTurns());
        // recentTurns ya excluye el turno en curso (PENDING/STREAMING); el mensaje de usuario actual sí está COMPLETED: se quita
        if (!all.isEmpty() && "USER".equals(all.get(all.size() - 1)[0]) && all.get(all.size() - 1)[1].equals(turn.message())) {
            return new ArrayList<>(all.subList(0, all.size() - 1));
        }
        return all;
    }

    private AiProperties.Profile profileOf(Subject subject) {
        return props.profiles().get(subject.role().name());
    }
}
