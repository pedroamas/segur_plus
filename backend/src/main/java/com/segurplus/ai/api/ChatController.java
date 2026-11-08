package com.segurplus.ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.ai.ChatService;
import com.segurplus.ai.ClientDisconnected;
import com.segurplus.ai.ConversationService;
import com.segurplus.auth.Subject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Chat con SSE y conversaciones privadas. La identidad sale del {@link Subject} (JWT); el cuerpo solo aporta
 * {@code conversationId} y {@code message}. Eventos SSE: {@code conversation, status, tool, delta, sources, verification, done, error}.
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "chat")
class ChatController {

    private final ChatService chat;
    private final ConversationService conversations;
    private final ObjectMapper mapper;

    ChatController(ChatService chat, ConversationService conversations, ObjectMapper mapper) {
        this.chat = chat;
        this.conversations = conversations;
        this.mapper = mapper;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Pregunta al asistente (respuesta SSE)",
            description = "Eventos: conversation, status, tool, delta, sources, verification, done, error. Conversación ajena ⇒ 404; sin motor de IA ⇒ 503.")
    ResponseEntity<StreamingResponseBody> chat(Subject subject, @RequestBody ChatRequest request) {
        ChatService.Turn turn = chat.prepare(subject, request); // validación y 404 antes de abrir el stream
        String correlation = MDC.get("correlationId");
        StreamingResponseBody body = out -> {
            if (correlation != null) {
                MDC.put("correlationId", correlation);
            }
            try {
                chat.run(turn, (event, data) -> {
                    try {
                        out.write(("event: " + event + "\ndata: " + mapper.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (IOException e) {
                        throw new ClientDisconnected(e); // el cliente cerró el SSE
                    }
                });
            } finally {
                MDC.remove("correlationId");
            }
        };
        // StreamingResponseBody: Spring confirma cabeceras y arranca el cuerpo en un hilo propio (sin carreras con la petición).
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no").body(body);
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
