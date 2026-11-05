package com.segurplus.audit;

import java.util.Map;
import java.util.UUID;

/** Evento de auditoría: solo metadatos (contadores, nombres, códigos); {@code details} nunca lleva contenido ni tokens. */
public record AuditEvent(
        AuditAction action,
        UUID userId,
        String resourceType,
        String resourceId,
        boolean allowed,
        String reasonCode,
        Channel channel,
        Map<String, Object> details) {

    public enum Channel { REST, AI_TOOL, RAG }

    public AuditEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static AuditEvent rest(AuditAction action, UUID userId, boolean allowed, String reasonCode) {
        return new AuditEvent(action, userId, null, null, allowed, reasonCode, Channel.REST, null);
    }

    public AuditEvent onResource(String type, Object id) {
        return new AuditEvent(action, userId, type, id == null ? null : id.toString(), allowed, reasonCode, channel, details);
    }

    public AuditEvent withDetails(Map<String, Object> newDetails) {
        return new AuditEvent(action, userId, resourceType, resourceId, allowed, reasonCode, channel, newDetails);
    }
}
