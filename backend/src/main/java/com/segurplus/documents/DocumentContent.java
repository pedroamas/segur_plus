package com.segurplus.documents;

import java.io.InputStream;

/** Contenido listo para servir. El que lo recibe debe cerrar {@code stream}. */
public record DocumentContent(InputStream stream, String contentType, String filename, long sizeBytes) {
}
