package com.segurplus.shared;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Único {@link Clock} de negocio (ADR 0006): fecha ficticia 2027-01-15 a mediodía, o reloj real en modo SYSTEM. */
@Configuration
class ClockConfig {

    @Bean
    Clock businessClock(ClockProperties props) {
        if (props.mode() == ClockProperties.Mode.SYSTEM) {
            return Clock.system(props.zone());
        }
        return Clock.fixed(props.fixedDate().atTime(12, 0).atZone(props.zone()).toInstant(), props.zone());
    }
}
