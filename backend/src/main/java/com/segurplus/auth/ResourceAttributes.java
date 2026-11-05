package com.segurplus.auth;

import java.util.Collection;
import java.util.UUID;

/**
 * Atributos de un recurso que la política necesita (ADR 0002). {@code allowedRoles} son los nombres de {@link Role};
 * los valores desconocidos se ignoran (no conceden nada).
 */
public record ResourceAttributes(Collection<String> allowedRoles, UUID ownerId, boolean deleted) {
}
