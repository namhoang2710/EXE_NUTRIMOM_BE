package vn.nutrimom.auth.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "password_reset_tokens", schema = "app")
public class PasswordResetTokenEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PasswordResetTokenStatus status = PasswordResetTokenStatus.PENDING;

    @Column(name = "request_ip_hash", length = 64)
    private String requestIpHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public boolean isExpiredAt(OffsetDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public PasswordResetTokenStatus getStatus() { return status; }
    public void setStatus(PasswordResetTokenStatus status) { this.status = status; }
    public String getRequestIpHash() { return requestIpHash; }
    public void setRequestIpHash(String requestIpHash) { this.requestIpHash = requestIpHash; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(OffsetDateTime usedAt) { this.usedAt = usedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
