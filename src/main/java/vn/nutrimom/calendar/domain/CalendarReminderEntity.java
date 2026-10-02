package vn.nutrimom.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Một mốc nhắc nhở do người dùng tự tạo (bảng {@code app.calendar_reminders}, migration V29).
 *
 * <p>{@code startsAt} là mốc tuyệt đối (UTC trên wire); {@code timezone} lưu múi giờ người dùng
 * đã chọn lúc tạo để render lại đúng ngày/giờ địa phương sau này.</p>
 *
 * <p>{@code remindAt} là cột <strong>dẫn xuất</strong> {@code startsAt - remindMinutesBefore}, do
 * service ghi lại mỗi lần một trong hai cột nguồn đổi — xem
 * {@code CalendarReminderService#applySchedule}. Job nhắc lịch nhờ đó chỉ phải so sánh phẳng
 * {@code remindAt <= now}, dùng được index, thay vì trừ interval theo cột trong câu query.</p>
 */
@Entity
@Table(name = "calendar_reminders", schema = "app")
public class CalendarReminderEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "pregnancy_id", length = 36)
    private String pregnancyId;

    @Column(name = "source_record_id", length = 36)
    private String sourceRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ReminderType type;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "facility_name", length = 255)
    private String facilityName;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "remind_minutes_before")
    private Integer remindMinutesBefore;

    @Column(name = "remind_at")
    private OffsetDateTime remindAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReminderStatus status = ReminderStatus.SCHEDULED;

    /** Thời điểm thực tế gửi lần gần nhất; chỉ để hiển thị, không phải khoá chống trùng. */
    @Column(name = "notified_at")
    private OffsetDateTime notifiedAt;

    /**
     * Mốc lặp gần nhất đã bắn — khoá chống gửi trùng của job.
     *
     * <p>Job claim bằng {@code WHERE lastFiredOccurrence IS NULL OR lastFiredOccurrence < :occurrence},
     * nên hai instance cùng quét thì chỉ một bên ghi được. Với mốc một lần thì nó được set đúng một
     * lần rồi {@code remindAt} về null, không bị quét lại.</p>
     */
    @Column(name = "last_fired_occurrence")
    private OffsetDateTime lastFiredOccurrence;

    /** null = mốc một lần. */
    @Enumerated(EnumType.STRING)
    @Column(name = "repeat_rule", length = 20)
    private RepeatRule repeatRule;

    /** Mỗi N ngày/tuần/tháng tuỳ {@link #repeatRule}. */
    @Column(name = "repeat_interval")
    private Integer repeatInterval;

    /** CSV các thứ cho {@link RepeatRule#WEEKLY}, vd {@code "MONDAY,WEDNESDAY,FRIDAY"}. */
    @Column(name = "repeat_days_of_week", length = 60)
    private String repeatDaysOfWeek;

    /** CSV các mốc giờ trong ngày, vd {@code "08:00,20:00"}; rỗng thì lấy giờ của startsAt. */
    @Column(name = "repeat_times_of_day", length = 60)
    private String repeatTimesOfDay;

    /** Ngày cuối còn lặp (theo múi giờ của dòng); null = không giới hạn. */
    @Column(name = "repeat_until")
    private LocalDate repeatUntil;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

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
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String value) { ownerUserId = value; }
    public String getPregnancyId() { return pregnancyId; }
    public void setPregnancyId(String value) { pregnancyId = value; }
    public String getSourceRecordId() { return sourceRecordId; }
    public void setSourceRecordId(String value) { sourceRecordId = value; }
    public ReminderType getType() { return type; }
    public void setType(ReminderType value) { type = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getFacilityName() { return facilityName; }
    public void setFacilityName(String value) { facilityName = value; }
    public OffsetDateTime getStartsAt() { return startsAt; }
    public void setStartsAt(OffsetDateTime value) { startsAt = value; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String value) { timezone = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public Integer getRemindMinutesBefore() { return remindMinutesBefore; }
    public void setRemindMinutesBefore(Integer value) { remindMinutesBefore = value; }
    public OffsetDateTime getRemindAt() { return remindAt; }
    public void setRemindAt(OffsetDateTime value) { remindAt = value; }
    public ReminderStatus getStatus() { return status; }
    public void setStatus(ReminderStatus value) { status = value; }
    public OffsetDateTime getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(OffsetDateTime value) { notifiedAt = value; }
    public OffsetDateTime getLastFiredOccurrence() { return lastFiredOccurrence; }
    public void setLastFiredOccurrence(OffsetDateTime value) { lastFiredOccurrence = value; }
    public RepeatRule getRepeatRule() { return repeatRule; }
    public void setRepeatRule(RepeatRule value) { repeatRule = value; }
    public Integer getRepeatInterval() { return repeatInterval; }
    public void setRepeatInterval(Integer value) { repeatInterval = value; }
    public String getRepeatDaysOfWeek() { return repeatDaysOfWeek; }
    public void setRepeatDaysOfWeek(String value) { repeatDaysOfWeek = value; }
    public String getRepeatTimesOfDay() { return repeatTimesOfDay; }
    public void setRepeatTimesOfDay(String value) { repeatTimesOfDay = value; }
    public LocalDate getRepeatUntil() { return repeatUntil; }
    public void setRepeatUntil(LocalDate value) { repeatUntil = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime value) { deletedAt = value; }
}
