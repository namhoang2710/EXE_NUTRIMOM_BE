package vn.nutrimom.pregnancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "pregnancy_audits", schema = "app")
public class PregnancyAuditEntity {
    public enum EventType { DUE_DATE_CHANGED }

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "pregnancy_id", nullable = false, length = 36)
    private String pregnancyId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private EventType eventType;

    @Column(name = "previous_due_date")
    private LocalDate previousDueDate;

    @Column(name = "new_due_date")
    private LocalDate newDueDate;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected PregnancyAuditEntity() { }

    public static PregnancyAuditEntity dueDateChanged(String pregnancyId, String userId,
                                                       LocalDate previous, LocalDate updated) {
        PregnancyAuditEntity audit = new PregnancyAuditEntity();
        audit.pregnancyId = pregnancyId;
        audit.userId = userId;
        audit.eventType = EventType.DUE_DATE_CHANGED;
        audit.previousDueDate = previous;
        audit.newDueDate = updated;
        return audit;
    }

    @PrePersist
    void beforeInsert() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}