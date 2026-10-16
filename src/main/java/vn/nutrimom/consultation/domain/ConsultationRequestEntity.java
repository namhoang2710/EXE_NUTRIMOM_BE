package vn.nutrimom.consultation.domain;

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
 * Một yêu cầu tư vấn do user tạo. Owner = {@code userId} (row-level security).
 * {@code expertUserId}/{@code slotId} để null khi RANDOM còn ở pool "đang chờ chuyên gia".
 */
@Entity
@Table(name = "consultation_requests", schema = "app")
public class ConsultationRequestEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "expert_user_id", length = 36)
    private String expertUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false, length = 20)
    private Specialty specialty;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_type", nullable = false, length = 20)
    private AssignmentType assignmentType;

    @Column(name = "slot_id", length = 36)
    private String slotId;

    // Immutable scheduling metadata survives cancellation/release of the live slot association.
    @Column(name = "scheduled_slot_id", length = 36)
    private String scheduledSlotId;
    @Column(name = "scheduled_date")
    private java.time.LocalDate scheduledDate;
    @Column(name = "scheduled_start_time")
    private java.time.LocalTime scheduledStartTime;
    @Column(name = "scheduled_end_time")
    private java.time.LocalTime scheduledEndTime;

    public void captureScheduledSlot(AvailabilitySlotEntity slot) {
        scheduledSlotId = slot.getId();
        scheduledDate = slot.getSlotDate();
        scheduledStartTime = slot.getStartTime();
        scheduledEndTime = slot.getEndTime();
        scheduledStartsAt = slot.getSlotDate().atTime(slot.getStartTime()).atZone(ConsultationTiming.ZONE).toOffsetDateTime();
        scheduledEndsAt = slot.getSlotDate().atTime(slot.getEndTime()).atZone(ConsultationTiming.ZONE).toOffsetDateTime();
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ConsultationStatus status;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "acceptance_opened_at")
    private OffsetDateTime acceptanceOpenedAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    public OffsetDateTime getAcceptanceOpenedAt() { return acceptanceOpenedAt; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }

    /** Immutable evidence, separate from updated_at. DIRECT bookings have no manual acceptance. */
    public void openAcceptance(OffsetDateTime at) {
        if (acceptanceOpenedAt == null) acceptanceOpenedAt = java.util.Objects.requireNonNull(at);
    }
    public void recordAcceptance(OffsetDateTime at) {
        java.util.Objects.requireNonNull(at);
        if (acceptanceOpenedAt != null && at.isBefore(acceptanceOpenedAt))
            throw new IllegalArgumentException("Acceptance precedes queue entry");
        if (acceptedAt == null) acceptedAt = at;
    }

    @Column(name = "scheduled_starts_at") private OffsetDateTime scheduledStartsAt;
    @Column(name = "scheduled_ends_at") private OffsetDateTime scheduledEndsAt;
    @Column(name = "preferred_date") private java.time.LocalDate preferredDate;
    @Column(name = "acceptance_deadline_at") private OffsetDateTime acceptanceDeadlineAt;
    @Column(name = "acceptance_policy_version") private Integer acceptancePolicyVersion;
    public OffsetDateTime getScheduledStartsAt() { return scheduledStartsAt; }
    public OffsetDateTime getScheduledEndsAt() { return scheduledEndsAt; }
    public java.time.LocalDate getPreferredDate() { return preferredDate; }
    public void setPreferredDate(java.time.LocalDate value) { preferredDate=value; }
    public OffsetDateTime getAcceptanceDeadlineAt() { return acceptanceDeadlineAt; }
    public Integer getAcceptancePolicyVersion() { return acceptancePolicyVersion; }
    public void openAcceptanceV2(OffsetDateTime at) {
        openAcceptance(at);
        if (acceptanceDeadlineAt == null) {
            acceptanceDeadlineAt=ConsultationTiming.acceptanceDeadline(at);
            acceptancePolicyVersion=2;
        }
    }

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
    public String getExpertUserId() { return expertUserId; }
    public void setExpertUserId(String value) { expertUserId = value; }
    public Specialty getSpecialty() { return specialty; }
    public void setSpecialty(Specialty value) { specialty = value; }
    public AssignmentType getAssignmentType() { return assignmentType; }
    public void setAssignmentType(AssignmentType value) { assignmentType = value; }
    public String getSlotId() { return slotId; }
    public void setSlotId(String value) { slotId = value; }
    public ConsultationStatus getStatus() { return status; }
    public void setStatus(ConsultationStatus value) { status = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime value) { completedAt = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
