package com.segurplus.auth.api;

import com.segurplus.auth.Role;
import java.util.UUID;

/** Elemento de {@code GET /api/v1/users} (selector de propietario). Solo campos mínimos de identificación. */
public record UserOption(UUID id, String employeeCode, String fullName, Role role) {
}
