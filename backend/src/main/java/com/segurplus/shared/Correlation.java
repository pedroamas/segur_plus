package com.segurplus.shared;

import org.slf4j.MDC;

/** Acceso al ID de correlación de la petición en curso (MDC). */
public final class Correlation {

    public static final String MDC_KEY = "correlationId";
    public static final String HEADER = "X-Correlation-Id";

    private Correlation() {
    }

    /** ID de la petición actual o {@code null} fuera de una petición. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
