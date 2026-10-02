package vn.nutrimom.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Ngoại lệ của một lần lặp (bảng {@code app.calendar_reminder_occurrences}, migration V30).
 *
 * <p>Chỉ tồn tại khi người dùng đã bấm "đã uống" hoặc "bỏ qua" cho đúng mốc đó. Nhờ vậy một nhắc
 * nhở hằng ngày kéo dài cả thai kỳ chỉ tốn số dòng bằng số lần người dùng thật sự bấm, thay vì
 * 280 dòng dựng sẵn.</p>
 */
@Entity
@Table(name = "calendar_reminder_occurrences", schema = "app")
public class CalendarReminderOccurrenceEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "reminder_id", nullable = false, length = 36)
    private String reminderId;

    @Column(name = "occurrence_at", nullable = false)
    private OffsetDateTime occurrenceAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OccurrenceStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() { updatedAt = OffsetDateTime.now(ZoneOffset.UTC); }

    public String getId() { return id; }
    public String getReminderId() { return reminderId; }
    public void setReminderId(String value) { reminderId = value; }
    public OffsetDateTime getOccurrenceAt() { return occurrenceAt; }
    public void setOccurrenceAt(OffsetDateTime value) { occurrenceAt = value; }
    public OccurrenceStatus getStatus() { return status; }
    public void setStatus(OccurrenceStatus value) { status = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
