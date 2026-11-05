package com.segurplus.shared;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Reloj de negocio (ADR 0006). {@code FIXED}: «hoy» = fixedDate; {@code SYSTEM}: reloj real. */
@ConfigurationProperties("segurplus.clock")
public record ClockProperties(Mode mode, LocalDate fixedDate, ZoneId zone) {

    public enum Mode { FIXED, SYSTEM }

    public ClockProperties {
        mode = mode == null ? Mode.FIXED : mode;
        fixedDate = fixedDate == null ? LocalDate.of(2027, 1, 15) : fixedDate;
        zone = zone == null ? ZoneId.of("Europe/Madrid") : zone;
    }
}
