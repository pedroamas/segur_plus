package com.segurplus.data;

/** Pólizas vendidas en el mes según su estado actual. */
public record PolicyStatusCounts(long active, long cancelled, long expired) {
}
