package com.segurplus.documents.api;

import java.util.List;

/** Página de resultados; {@code totalElements} cuenta solo lo que el usuario puede leer (misma Specification). */
public record DocumentPage(List<DocumentSummary> items, int page, int size, long totalElements, int totalPages) {
}
