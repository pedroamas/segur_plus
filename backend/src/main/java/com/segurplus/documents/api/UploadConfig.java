package com.segurplus.documents.api;

import java.util.List;

/** Límites de subida vigentes en el servidor (una sola fuente de verdad para la UI). */
public record UploadConfig(long maxBytes, List<String> allowedExtensions) {
}
