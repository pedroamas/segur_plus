package com.segurplus.data;

import com.segurplus.shared.Money;

/** Pólizas vendidas en el mes (la venta es la póliza) y su prima anual. */
public record SalesSummary(long policyCount, Money premiums) {
}
