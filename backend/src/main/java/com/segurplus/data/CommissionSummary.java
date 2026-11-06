package com.segurplus.data;

import com.segurplus.shared.Money;

/**
 * Comisiones de empleado del mes: {@code accrued} = suma de ACCRUAL (el número ancla), {@code clawbacks} = suma de CLAWBACK
 * (negativa, cancelaciones del mes) y {@code net} = accrued + clawbacks.
 */
public record CommissionSummary(Money accrued, Money clawbacks, Money net) {
}
