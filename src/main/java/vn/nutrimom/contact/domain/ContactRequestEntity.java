package vn.nutrimom.contact.domain;

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
 * Một yêu cầu hỗ trợ (hộp thư Contact) do user gửi. Owner = {@code userId} (row-level security).
 * Admin giải đáp qua điện thoại rồi đánh dấu hoàn tất; {@code completedBy} lưu admin xử lý.
 */
@Entity
@Table(name = "contact_requests", schema = "app")
public class ContactRequestEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "topic", nullable = false, length = 20)
    private ContactTopic topic;

    @Column(name = "message", nullable = false, columnDefinition = "nvarchar(max)")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ContactRequestStatus status;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "completed_by", length = 36)
    private String completedBy;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

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
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public ContactTopic getTopic() { return topic; }
    public void setTopic(ContactTopic value) { topic = value; }
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value; }
    public ContactRequestStatus getStatus() { return status; }
    public void setStatus(ContactRequestStatus value) { status = value; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime value) { completedAt = value; }
    public String getCompletedBy() { return completedBy; }
    public void setCompletedBy(String value) { completedBy = value; }
    public OffsetDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(OffsetDateTime value) { cancelledAt = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
