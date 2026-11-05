package com.segurplus.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuración de sesión (ADR 0007). Falla al arrancar si el secreto es corto o el access token dura más de 15 minutos. */
@ConfigurationProperties("segurplus.auth")
public record AuthProperties(String jwtSecret, String issuer, Duration accessTokenTtl, Duration refreshTokenTtl,
                             int bcryptStrength) {

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("segurplus.auth.jwt-secret must be at least 32 bytes (set JWT_SECRET)");
        }
        issuer = issuer == null ? "segurplus" : issuer;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(10) : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(7) : refreshTokenTtl;
        if (accessTokenTtl.compareTo(Duration.ofMinutes(10)) < 0 || accessTokenTtl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalStateException("segurplus.auth.access-token-ttl must be between 10 and 15 minutes (ADR 0007)");
        }
        bcryptStrength = bcryptStrength == 0 ? 10 : bcryptStrength;
    }
}
