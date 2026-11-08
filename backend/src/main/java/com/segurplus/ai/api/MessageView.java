package com.segurplus.ai.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mensaje de una conversación propia. {@code sources} se RE-AUTORIZA al leer: solo documentos que el usuario puede leer ahora.
 * {@code tools} lleva nombre y parámetros resueltos (no filas). Sin identificadores internos de Dify.
 */
public record MessageView(UUID id, String role, String content, String status, List<Source> sources, List<ToolUse> tools,
        Object verification, Integer tokensIn, Integer tokensOut, Integer latencyMs, Instant createdAt) {

    public record Source(UUID documentId, String code, String title, boolean cited) {
    }

    public record ToolUse(String tool, Object params) {
    }
}
