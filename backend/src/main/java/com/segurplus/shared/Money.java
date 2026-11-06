package com.segurplus.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Importe con moneda. {@code amount} es una cadena decimal con 2 decimales (nunca un número JSON: sin pérdida de precisión). */
public record Money(String amount, String currency) {

    public static final String EUR = "EUR";

    public static Money eur(BigDecimal value) {
        return new Money(value.setScale(2, RoundingMode.HALF_UP).toPlainString(), EUR);
    }
}
