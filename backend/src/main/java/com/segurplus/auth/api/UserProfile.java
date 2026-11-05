package com.segurplus.auth.api;

import com.segurplus.auth.Role;
import java.util.UUID;

/** Respuesta de {@code GET /api/v1/users/me}. No incluye salario ni datos de Dify. */
public record UserProfile(UUID id, String employeeCode, String email, String fullName, Role role, String department) {
}
