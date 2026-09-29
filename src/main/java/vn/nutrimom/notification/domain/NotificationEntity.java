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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Một thông báo in-app gửi tới đúng một người nhận ({@code userId} = owner, row-level security).
 *
 * <p>{@code readAt} null nghĩa là chưa đọc. Đánh dấu đã đọc là idempotent (spec mục 18) nên chỉ set
 * khi còn null, gọi lại lần nữa giữ nguyên mốc thời gian cũ.</p>
 *
 * <p>{@code sourceType}/{@code sourceId} trỏ ngược về bản ghi gốc (consultation, contact request...)
 * để tra cứu khi cần; app điều hướng bằng {@code deepLink} đã dựng sẵn.</p>
 */
@Entity
@Table(name = "notifications", schema = "app")
public class NotificationEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    @Column(name = "deep_link", length = 300)
    private String deepLink;

    @Column(name = "source_type", length = 40)
    private String sourceType;

    @Column(name = "source_id", length = 36)
    private String sourceId;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * {@code createdAt} được cắt về micro giây vì nó là một nửa khoá cursor.
     *
     * <p>{@code OffsetDateTime.now()} cho tới nano giây, trong khi cột lưu ở độ chính xác thấp hơn
     * (H2 micro giây). Nếu giữ nguyên nano, giá trị trong bộ nhớ sẽ lớn hơn giá trị thật dưới DB,
     * làm dòng cuối trang trước lọt lại vào trang sau. Cắt ở đây để hai bên khớp tuyệt đối.</p>
     */
    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType value) { type = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getBody() { return body; }
    public void setBody(String value) { body = value; }
    public String getDeepLink() { return deepLink; }
    public void setDeepLink(String value) { deepLink = value; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String value) { sourceType = value; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String value) { sourceId = value; }
    public OffsetDateTime getReadAt() { return readAt; }
    public void setReadAt(OffsetDateTime value) { readAt = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
