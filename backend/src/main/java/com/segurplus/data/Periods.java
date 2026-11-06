package com.segurplus.data;

import java.time.LocalDate;
import java.time.YearMonth;

/** Rango de fechas de un mes natural (inclusivo). */
record Periods(LocalDate from, LocalDate to) {

    static Periods of(YearMonth month) {
        return new Periods(month.atDay(1), month.atEndOfMonth());
    }
}
