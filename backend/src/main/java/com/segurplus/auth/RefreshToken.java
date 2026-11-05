package com.segurplus.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Fila de {@code refresh_tokens}: solo el hash SHA-256 del token; nunca el valor en claro. */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

    @Id
    private UUID id;
    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "token_hash", length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String tokenHash;
    @Column(name = "family_id")
    private UUID familyId;
    @Column(name = "issued_at")
    private Instant issuedAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "replaced_by")
    private UUID replacedBy;

    protected RefreshToken() {
    }

    RefreshToken(UUID userId, String tokenHash, UUID familyId, Instant issuedAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    UUID getId() { return id; }
    UUID getUserId() { return userId; }
    UUID getFamilyId() { return familyId; }
    Instant getExpiresAt() { return expiresAt; }
    Instant getRevokedAt() { return revokedAt; }

    void rotateTo(UUID newId, Instant now) {
        this.revokedAt = now;
        this.replacedBy = newId;
    }
}
