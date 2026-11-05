package com.segurplus.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code username}: código de empleado (DIR001…) o email. */
public record LoginRequest(@NotBlank @Size(max = 254) String username, @NotBlank @Size(max = 200) String password) {
}
