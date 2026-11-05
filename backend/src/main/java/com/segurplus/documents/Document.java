package com.segurplus.documents;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Metadatos del documento. Los nombres de atributo {@code id}, {@code deletedAt}, {@code ownerId} y {@code allowedRoles}
 * son los que usa {@code AccessPolicyService.toSpecification}. Nunca se expone fuera del paquete (los DTOs viven en {@code api}).
 */
@Entity
@Table(name = "documents")
class Document {

    @Id
    private UUID id;
    private String code;
    private String title;
    @Enumerated(EnumType.STRING)
    @Column(name = "file_type")
    private FileType fileType;
    @Enumerated(EnumType.STRING)
    private DocCategory category;
    @Enumerated(EnumType.STRING)
    private Kb kb;
    @Enumerated(EnumType.STRING)
    private Classification classification;
    @Column(name = "owner_id")
    private UUID ownerId;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_roles", columnDefinition = "text[]")
    private String[] allowedRoles;
    @Column(name = "storage_key")
    private String storageKey;
    @Column(name = "checksum_sha256", length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String checksumSha256;
    @Column(name = "size_bytes")
    private long sizeBytes;
    @Column(name = "index_status")
    private String indexStatus = "PENDING";
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Document() {
    }

    Document(UUID id, String code, String title, FileType fileType, DocCategory category, Kb kb, Classification classification,
            UUID ownerId, List<String> allowedRoles, String storageKey, String checksumSha256, long sizeBytes, UUID createdBy,
            Instant now) {
        this.id = id;
        this.code = code;
        this.title = title;
        this.fileType = fileType;
        this.category = category;
        this.kb = kb;
        this.classification = classification;
        this.ownerId = ownerId;
        this.allowedRoles = allowedRoles.toArray(String[]::new);
        this.storageKey = storageKey;
        this.checksumSha256 = checksumSha256;
        this.sizeBytes = sizeBytes;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() { return id; }
    String getCode() { return code; }
    String getTitle() { return title; }
    FileType getFileType() { return fileType; }
    DocCategory getCategory() { return category; }
    Kb getKb() { return kb; }
    Classification getClassification() { return classification; }
    UUID getOwnerId() { return ownerId; }
    List<String> getAllowedRoles() { return List.of(allowedRoles); }
    String getStorageKey() { return storageKey; }
    String getChecksumSha256() { return checksumSha256; }
    long getSizeBytes() { return sizeBytes; }
    String getIndexStatus() { return indexStatus; }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
    Instant getDeletedAt() { return deletedAt; }
}
