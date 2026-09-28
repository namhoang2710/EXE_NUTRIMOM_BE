package vn.nutrimom.consultation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Chuyên gia nghỉ trọn một ngày. Tách riêng khỏi các ô {@code CLOSED} để bật rồi tắt
 * "nghỉ cả ngày" không xóa mất những khung giờ chuyên gia đã cố ý đóng tay trước đó.
 */
@Entity
@Table(name = "expert_day_offs", schema = "app",
        uniqueConstraints = @UniqueConstraint(name = "ux_expert_day_offs_expert_date",
                columnNames = {"expert_user_id", "off_date"}))
public class ExpertDayOffEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "expert_user_id", nullable = false, length = 36)
    private String expertUserId;

    @Column(name = "off_date", nullable = false)
    private LocalDate offDate;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public String getExpertUserId() { return expertUserId; }
    public void setExpertUserId(String value) { expertUserId = value; }
    public LocalDate getOffDate() { return offDate; }
    public void setOffDate(LocalDate value) { offDate = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
