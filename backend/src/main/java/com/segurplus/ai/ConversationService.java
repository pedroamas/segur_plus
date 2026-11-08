package com.segurplus.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.ai.api.ConversationSummary;
import com.segurplus.ai.api.MessageView;
import com.segurplus.auth.Subject;
import com.segurplus.documents.DocumentCitationService;
import com.segurplus.documents.DocumentCitationService.Citable;
import com.segurplus.shared.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversaciones privadas (ADR 0003 §8, H-15): la tabla propia es la fuente de verdad y SOLO el propietario las ve; ni MANAGER
 * lee las ajenas. Ajena, inexistente o borrada ⇒ el mismo 404 sin consultar a Dify. Las citas guardadas se RE-AUTORIZAN al
 * releer: si el rol perdió acceso al documento, la cita desaparece (el texto ya dicho no se «des-dice», H-13).
 */
@Service
public class ConversationService {

    static final int LIST_LIMIT = 100;
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final DocumentCitationService citations;

    ConversationService(JdbcClient jdbc, ObjectMapper mapper, DocumentCitationService citations) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.citations = citations;
    }

    @Transactional(readOnly = true)
    public List<ConversationSummary> list(Subject subject) {
        return jdbc.sql("SELECT id, title, created_at, updated_at FROM conversations "
                + "WHERE user_id = :u AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT " + LIST_LIMIT)
                .param("u", subject.userId())
                .query((rs, n) -> new ConversationSummary(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    @Transactional(readOnly = true)
    public List<MessageView> messages(Subject subject, UUID conversationId) {
        requireOwned(subject, conversationId);
        record Row(UUID id, String role, String content, String sources, String tools, Integer in, Integer out, Integer latency,
                String status, String verification, Instant createdAt) { }
        List<Row> rows = jdbc.sql("SELECT id, role, content, sources::text AS sources, tools::text AS tools, tokens_in, tokens_out, "
                + "latency_ms, status, verification::text AS verification, created_at FROM messages "
                + "WHERE conversation_id = :c ORDER BY created_at, id")
                .param("c", conversationId)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("content"),
                        rs.getString("sources"), rs.getString("tools"), (Integer) rs.getObject("tokens_in"),
                        (Integer) rs.getObject("tokens_out"), (Integer) rs.getObject("latency_ms"), rs.getString("status"),
                        rs.getString("verification"), rs.getTimestamp("created_at").toInstant()))
                .list();
        List<List<Map<String, Object>>> sourcesPerRow = rows.stream().map(r -> readList(r.sources())).toList();
        List<UUID> docIds = sourcesPerRow.stream().flatMap(List::stream).map(s -> uuid(s.get("documentId"))).filter(java.util.Objects::nonNull).toList();
        Map<UUID, Citable> readable = citations.readable(subject, docIds);
        List<MessageView> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            List<MessageView.Source> sources = new ArrayList<>();
            for (Map<String, Object> s : sourcesPerRow.get(i)) {
                Citable doc = readable.get(uuid(s.get("documentId")));
                if (doc != null) { // re-autorizada ahora; si ya no puede leerlo, no se devuelve
                    sources.add(new MessageView.Source(doc.id(), doc.code(), doc.title(), Boolean.TRUE.equals(s.get("cited"))));
                }
            }
            List<MessageView.ToolUse> tools = new ArrayList<>();
            for (Map<String, Object> t : readList(r.tools())) {
                tools.add(new MessageView.ToolUse(String.valueOf(t.get("tool")), t.get("params")));
            }
            Object verification = null;
            try {
                verification = r.verification() == null ? null : mapper.readValue(r.verification(), Object.class);
            } catch (JsonProcessingException e) {
                // sin verificación legible: se omite
            }
            out.add(new MessageView(r.id(), r.role(), r.content(), r.status(), sources, tools, verification, r.in(), r.out(),
                    r.latency(), r.createdAt()));
        }
        return out;
    }

    @Transactional
    public void delete(Subject subject, UUID conversationId) {
        requireOwned(subject, conversationId);
        jdbc.sql("UPDATE conversations SET deleted_at = now(), updated_at = now() WHERE id = :c AND user_id = :u")
                .param("c", conversationId).param("u", subject.userId()).update();
    }

    /** 404 si la conversación no existe, está borrada o no es del usuario del JWT (indistinguibles). */
    public void requireOwned(Subject subject, UUID conversationId) {
        Optional<UUID> found = jdbc.sql("SELECT id FROM conversations WHERE id = :c AND user_id = :u AND deleted_at IS NULL")
                .param("c", conversationId).param("u", subject.userId()).query(UUID.class).optional();
        if (found.isEmpty()) {
            throw ApiException.notFound();
        }
    }

    UUID create(Subject subject, String firstMessage) {
        String title = firstMessage.strip().replaceAll("\\s+", " ");
        title = title.length() > 80 ? title.substring(0, 80) : title;
        return jdbc.sql("INSERT INTO conversations (user_id, title) VALUES (:u, :t) RETURNING id")
                .param("u", subject.userId()).param("t", title).query(UUID.class).single();
    }

    UUID addMessage(UUID conversationId, String role, String content, String status) {
        return jdbc.sql("INSERT INTO messages (conversation_id, role, content, status) VALUES (:c, :r, :t, :s) RETURNING id")
                .param("c", conversationId).param("r", role).param("t", content).param("s", status).query(UUID.class).single();
    }

    void updateStatus(UUID messageId, String status) {
        jdbc.sql("UPDATE messages SET status = :s WHERE id = :id").param("s", status).param("id", messageId).update();
    }

    void finishAssistantMessage(UUID messageId, UUID conversationId, String content, String status, Object sources, Object tools,
            Object verification, int tokensIn, int tokensOut, long latencyMs) {
        jdbc.sql("UPDATE messages SET content = :content, status = :status, sources = CAST(:sources AS jsonb), "
                + "tools = CAST(:tools AS jsonb), verification = CAST(:verification AS jsonb), tokens_in = :ti, tokens_out = :to, "
                + "latency_ms = :lat WHERE id = :id")
                .param("content", content).param("status", status).param("sources", json(sources)).param("tools", json(tools))
                .param("verification", verification == null ? null : json(verification)).param("ti", tokensIn).param("to", tokensOut)
                .param("lat", (int) Math.min(latencyMs, Integer.MAX_VALUE)).param("id", messageId).update();
        jdbc.sql("UPDATE conversations SET updated_at = now() WHERE id = :c").param("c", conversationId).update();
    }

    /** Últimos turnos COMPLETED (texto de usuario y asistente) para dar continuidad; sin fragmentos ni resultados de herramientas. */
    List<String[]> recentTurns(UUID conversationId, int turns) {
        List<String[]> rows = new ArrayList<>(jdbc.sql("SELECT role, content FROM messages WHERE conversation_id = :c AND status = 'COMPLETED' "
                + "ORDER BY created_at DESC, id DESC LIMIT :n").param("c", conversationId).param("n", turns * 2)
                .query((rs, n) -> new String[] {rs.getString("role"), rs.getString("content")}).list());
        java.util.Collections.reverse(rows);
        return rows;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<Map<String, Object>> readList(String json) {
        try {
            return json == null ? List.of() : mapper.readValue(json, LIST_OF_MAPS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static UUID uuid(Object o) {
        try {
            return o == null ? null : UUID.fromString(o.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
