package vn.nutrimom.auth.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "otp_challenges", schema = "app")
public class OtpChallengeEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;
    @Column(name = "phone", nullable = false, length = 20)
    private String phone;
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20)
    private OtpPurpose purpose;
    @Column(name = "code_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String codeHash;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OtpStatus status;
    @Column(name = "attempts", nullable = false)
    private int attempts;
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;
    @Column(name = "device_id", length = 100)
    private String deviceId;
    @Column(name = "request_ip_hash", length = 64, columnDefinition = "CHAR(64)")
    private String requestIpHash;
    @Column(name = "terms_accepted", nullable = false)
    private boolean termsAccepted;
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
    @Column(name = "resend_available_at", nullable = false)
    private OffsetDateTime resendAvailableAt;
    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public OtpPurpose getPurpose() { return purpose; }
    public void setPurpose(OtpPurpose purpose) { this.purpose = purpose; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public OtpStatus getStatus() { return status; }
    public void setStatus(OtpStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getRequestIpHash() { return requestIpHash; }
    public void setRequestIpHash(String value) { requestIpHash = value; }
    public boolean isTermsAccepted() { return termsAccepted; }
    public void setTermsAccepted(boolean value) { termsAccepted = value; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime value) { expiresAt = value; }
    public OffsetDateTime getResendAvailableAt() { return resendAvailableAt; }
    public void setResendAvailableAt(OffsetDateTime value) { resendAvailableAt = value; }
    public OffsetDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(OffsetDateTime value) { verifiedAt = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
