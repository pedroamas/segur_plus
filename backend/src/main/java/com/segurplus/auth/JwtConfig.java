package com.segurplus.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
class JwtConfig {

    private static SecretKey key(AuthProperties props) {
        return new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties props) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(props)));
    }

    /** Valida firma HS256, emisor y caducidad con el reloj de {@link TokenTime} y sin tolerancia (el defecto sería 60 s). */
    @Bean
    JwtDecoder jwtDecoder(AuthProperties props, TokenTime time) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(props)).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(java.time.Duration.ZERO);
        timestamps.setClock(time.asClock());
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(props.issuer()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder(AuthProperties props) {
        return new BCryptPasswordEncoder(props.bcryptStrength());
    }
}
