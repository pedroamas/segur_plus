package com.segurplus.data;

import java.util.Set;
import java.util.UUID;

/**
 * Alcance de datos de un usuario (ADR 0005): {@code OWN} solo lo propio, {@code TEAM} los empleados comerciales, {@code GLOBAL}
 * todo. {@code aggregateOnly}: solo totales, sin desglose por empleado (ACCOUNTANT).
 */
record DataScope(Kind kind, Set<UUID> employeeIds, boolean aggregateOnly) {

    enum Kind { OWN, TEAM, GLOBAL }

    private static final Set<UUID> NONE = Set.of(new UUID(0, 0));

    boolean global() {
        return kind == Kind.GLOBAL;
    }

    /** Parámetro de IN para las consultas: nunca vacío (un IN vacío no es portable); si el alcance no tiene nadie, no casa con ninguna fila. */
    Set<UUID> idsParam() {
        return employeeIds.isEmpty() ? NONE : employeeIds;
    }
}
