package com.segurplus.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * Tiempo REAL para la expiración del access JWT y del refresh (ADR 0006/0007). Es el único sitio, fuera de
 * {@code shared}, donde se permite leer el reloj del sistema (lo verifica el test de arquitectura).
 * Los tests lo sustituyen para «avanzar» el reloj y probar la caducidad.
 */
@Component
public class TokenTime {

    public Instant now() {
        return Instant.now();
    }

    /** Vista como {@link Clock} para el validador de caducidad del JWT. */
    Clock asClock() {
        TokenTime self = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return self.now();
            }
        };
    }
}
