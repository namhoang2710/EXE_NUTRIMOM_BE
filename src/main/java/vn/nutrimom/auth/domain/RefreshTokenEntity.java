package vn.nutrimom.auth.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens", schema = "app")
public class RefreshTokenEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "CHAR(64)")
    private String tokenHash;
    @Column(name = "device_id", length = 100)
    private String deviceId;
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;
    @Column(name = "replaced_by_token_id", length = 36)
    private String replacedByTokenId;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public boolean isUsableAt(OffsetDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
    public String getReplacedByTokenId() { return replacedByTokenId; }
    public void setReplacedByTokenId(String value) { replacedByTokenId = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
