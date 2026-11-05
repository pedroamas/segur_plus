package com.segurplus.documents.api;

import com.segurplus.auth.Role;
import com.segurplus.documents.Classification;
import com.segurplus.documents.DocCategory;
import com.segurplus.documents.Kb;
import java.util.List;
import java.util.UUID;

/** Metadatos de una subida (ya validados en sintaxis). La política de acceso los valida de nuevo en el servicio. */
public record UploadMetadata(String code, String title, Kb kb, DocCategory category, Classification classification,
                             UUID ownerId, List<Role> allowedRoles) {
}
