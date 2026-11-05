package com.segurplus.shared;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Semilla de desarrollo (NUNCA producción). Las contraseñas llegan solo por configuración local/entorno:
 * {@code SEGURPLUS_SEED_DEFAULT_PASSWORD} para los 7 usuarios o {@code SEGURPLUS_SEED_PASSWORDS_<CODIGO>} por usuario.
 * {@code documentsSpecPath}: ruta a permissions-spec.yaml para sembrar documentos de PRUEBA (fixtures pequeños).
 */
@ConfigurationProperties("segurplus.seed")
public record SeedProperties(boolean enabled, String defaultPassword, Map<String, String> passwords,
                             String documentsSpecPath) {

    public SeedProperties {
        passwords = passwords == null ? Map.of() : Map.copyOf(passwords);
    }

    public String passwordFor(String employeeCode) {
        String specific = passwords.get(employeeCode) != null ? passwords.get(employeeCode) : passwords.get(employeeCode.toLowerCase());
        String password = specific != null ? specific : defaultPassword;
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Seed enabled but no password configured for " + employeeCode
                    + " (set SEGURPLUS_SEED_DEFAULT_PASSWORD or SEGURPLUS_SEED_PASSWORDS_" + employeeCode + ")");
        }
        return password;
    }
}
