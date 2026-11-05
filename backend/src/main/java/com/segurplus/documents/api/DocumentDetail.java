package com.segurplus.documents.api;

import com.segurplus.documents.Classification;
import com.segurplus.documents.DocCategory;
import com.segurplus.documents.FileType;
import com.segurplus.documents.Kb;
import java.time.Instant;
import java.util.UUID;

/** Detalle de un documento. {@code viewable}: visor PDF inline; {@code previewable}: preview Excel en servidor. */
public record DocumentDetail(UUID id, String code, String title, FileType fileType, DocCategory category, Kb kb,
                             Classification classification, UUID ownerId, long sizeBytes, String checksumSha256,
                             String indexStatus, boolean viewable, boolean previewable, Instant createdAt, Instant updatedAt) {
}
