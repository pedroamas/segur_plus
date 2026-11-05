package com.segurplus.auth;

/** Resultado de la política. {@code reasonCode} va a {@code audit_logs}; al cliente solo llega 404 (o 403 en escrituras). */
public record Decision(boolean allowed, String reasonCode) {

    public static Decision allow(String reasonCode) {
        return new Decision(true, reasonCode);
    }

    public static Decision deny(String reasonCode) {
        return new Decision(false, reasonCode);
    }
}
