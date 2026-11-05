package com.segurplus.auth;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Emite el access JWT (HS256) con {@code sub}, {@code role} y {@code code}. Vida de 10-15 minutos, tiempo real. */
@Service
class AccessTokenService {

    private final JwtEncoder encoder;
    private final AuthProperties props;
    private final TokenTime time;

    AccessTokenService(JwtEncoder encoder, AuthProperties props, TokenTime time) {
        this.encoder = encoder;
        this.props = props;
        this.time = time;
    }

    String issue(User user) {
        Instant now = time.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim(Subject.CLAIM_ROLE, user.getRole().name())
                .claim(Subject.CLAIM_CODE, user.getEmployeeCode())
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    long expiresInSeconds() {
        return props.accessTokenTtl().toSeconds();
    }
}
