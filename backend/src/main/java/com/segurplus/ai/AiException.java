package com.segurplus.ai;

/** Fallo del motor de IA. {@code started}: ya se emitió texto al cliente (no se puede reintentar de forma transparente). */
public class AiException extends RuntimeException {

    public enum Kind { TIMEOUT, UNAVAILABLE, REJECTED, NOT_CONFIGURED }

    private final Kind kind;
    private final boolean started;

    public AiException(Kind kind, String message, boolean started, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.started = started;
    }

    public AiException(Kind kind, String message) {
        this(kind, message, false, null);
    }

    public Kind kind() {
        return kind;
    }

    public boolean started() {
        return started;
    }

    AiException started(boolean value) {
        return new AiException(kind, getMessage(), value, getCause());
    }
}
