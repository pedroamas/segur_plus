package com.segurplus.ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.ai.ChatService;
import com.segurplus.ai.ClientDisconnected;
import com.segurplus.ai.ConversationService;
import com.segurplus.auth.Subject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat con SSE y conversaciones privadas. La identidad sale del {@link Subject} (JWT); el cuerpo solo aporta
 * {@code conversationId} y {@code message}. Eventos SSE: {@code conversation, status, tool, delta, sources, verification, done, error}.
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "chat")
class ChatController {

    /** Tope del emisor: ligeramente por encima del tope de 120 s de generación (ADR 0003). */
    private static final long EMITTER_TIMEOUT_MS = 150_000;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatController.class);

    private final ChatService chat;
    private final ConversationService conversations;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    ChatController(ChatService chat, ConversationService conversations, ObjectMapper mapper) {
        this.chat = chat;
        this.conversations = conversations;
        this.mapper = mapper;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Pregunta al asistente (respuesta SSE)",
            description = "Eventos: conversation, status, tool, delta, sources, verification, done, error. Conversación ajena ⇒ 404; sin motor de IA ⇒ 503.")
    SseEmitter chat(Subject subject, @RequestBody ChatRequest request) {
        ChatService.Turn turn = chat.prepare(subject, request); // validación y 404 antes de abrir el stream
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        String correlation = MDC.get("correlationId");
        executor.submit(() -> {
            if (correlation != null) {
                MDC.put("correlationId", correlation);
            }
            try {
                chat.run(turn, (event, data) -> {
                    try {
                        emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
                    } catch (IOException | IllegalStateException e) {
                        log.info("SSE client gone ({} cause {})", e.getClass().getSimpleName(), e.getCause() == null ? null : e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
                        throw new ClientDisconnected(e);
                    }
                });
            } catch (Throwable t) {
                log.error("Chat turn aborted unexpectedly ({})", t.getClass().getName());
            } finally {
                emitter.complete();
                MDC.remove("correlationId");
            }
        });
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    @GetMapping("/conversations")
    @Operation(summary = "Mis conversaciones", description = "Solo las del usuario del token.")
    List<ConversationSummary> list(Subject subject) {
        return conversations.list(subject);
    }

    @GetMapping("/conversations/{id}/messages")
    @Operation(summary = "Mensajes de una conversación propia", description = "Ajena o inexistente ⇒ 404 (también para MANAGER). Las fuentes se re-autorizan al leer.")
    List<MessageView> messages(Subject subject, @PathVariable UUID id) {
        return conversations.messages(subject, id);
    }

    @DeleteMapping("/conversations/{id}")
    @Operation(summary = "Borrar una conversación propia", description = "Ajena o inexistente ⇒ 404.")
    ResponseEntity<Void> delete(Subject subject, @PathVariable UUID id) {
        conversations.delete(subject, id);
        return ResponseEntity.noContent().build();
    }
}
