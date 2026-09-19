package vn.nutrimom.medicalrecord.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "medical_record_audits", schema = "app")
public class MedicalRecordAuditEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "medical_record_id", nullable = false, length = 36)
    private String medicalRecordId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public String getMedicalRecordId() { return medicalRecordId; }
    public void setMedicalRecordId(String value) { medicalRecordId = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
