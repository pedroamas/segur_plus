package com.segurplus.ai.api;

import java.time.Instant;
import java.util.UUID;

/** Conversación propia (id, título y fechas; nunca contenido). */
public record ConversationSummary(UUID id, String title, Instant createdAt, Instant updatedAt) {
}
