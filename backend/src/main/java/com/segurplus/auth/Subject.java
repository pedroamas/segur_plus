package com.segurplus.auth;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Quién actúa. Se construye SOLO desde el JWT validado, nunca desde parámetros o cuerpos del cliente. */
public record Subject(UUID userId, Role role, String employeeCode) {

    static final String CLAIM_ROLE = "role";
    static final String CLAIM_CODE = "code";

    public static Subject fromJwt(Jwt jwt) {
        return new Subject(UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString(CLAIM_ROLE)),
                jwt.getClaimAsString(CLAIM_CODE));
    }
}
