package com.segurplus.ai;

/** El cliente cerró el SSE: aborta la generación sin tratarla como fallo del motor. */
public class ClientDisconnected extends RuntimeException {

    public ClientDisconnected(Throwable cause) {
        super("client disconnected", cause);
    }
}
