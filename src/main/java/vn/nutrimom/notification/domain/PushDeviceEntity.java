package vn.nutrimom.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Một thiết bị đã đăng ký nhận push của user (spec mục 18 "POST /devices"), upsert theo
 * {@code (userId, deviceId)}.
 *
 * <p>Push token không bao giờ nằm ở dạng thô: {@code pushTokenCipher} là bản AES-256-GCM base64 do
 * {@code PushTokenCipher} sinh ra, còn {@code pushTokenHash} là SHA-256 hex dùng để tra cứu vì
 * ciphertext dùng IV ngẫu nhiên nên không tất định. Không thêm getter trả token thô và không log
 * token (spec mục 21 "Không log token").</p>
 */
@Entity
@Table(name = "push_devices", schema = "app")
public class PushDeviceEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "device_id", nullable = false, length = 100)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    private DevicePlatform platform;

    @Column(name = "push_token_cipher", nullable = false, length = 600)
    private String pushTokenCipher;

    @Column(name = "push_token_hash", nullable = false, length = 64)
    private String pushTokenHash;

    @Column(name = "app_version", length = 30)
    private String appVersion;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String value) { deviceId = value; }
    public DevicePlatform getPlatform() { return platform; }
    public void setPlatform(DevicePlatform value) { platform = value; }
    public String getPushTokenCipher() { return pushTokenCipher; }
    public void setPushTokenCipher(String value) { pushTokenCipher = value; }
    public String getPushTokenHash() { return pushTokenHash; }
    public void setPushTokenHash(String value) { pushTokenHash = value; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String value) { appVersion = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime value) { lastSeenAt = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
