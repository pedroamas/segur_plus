package com.segurplus.documents.api;

import com.segurplus.documents.Classification;
import com.segurplus.documents.DocCategory;
import com.segurplus.documents.FileType;
import com.segurplus.documents.Kb;
import java.time.Instant;
import java.util.UUID;

/** Elemento de listado. No expone la clave de almacenamiento ni la regla de acceso (allowed_roles). */
public record DocumentSummary(UUID id, String code, String title, FileType fileType, DocCategory category, Kb kb,
                              Classification classification, long sizeBytes, Instant createdAt) {
}
