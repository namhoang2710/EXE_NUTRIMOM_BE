package vn.nutrimom.medicalrecord.domain;

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

@Entity
@Table(name = "medical_records", schema = "app")
public class MedicalRecordEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "pregnancy_id", nullable = false, length = 36)
    private String pregnancyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private MedicalRecordCategory category;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "facility_name", length = 255)
    private String facilityName;

    @Column(name = "clinician_name", length = 255)
    private String clinicianName;

    @Column(name = "summary", columnDefinition = "nvarchar(max)")
    private String summary;

    @Column(name = "note", columnDefinition = "nvarchar(max)")
    private String note;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String value) { ownerUserId = value; }
    public String getPregnancyId() { return pregnancyId; }
    public void setPregnancyId(String value) { pregnancyId = value; }
    public MedicalRecordCategory getCategory() { return category; }
    public void setCategory(MedicalRecordCategory value) { category = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime value) { occurredAt = value; }
    public String getFacilityName() { return facilityName; }
    public void setFacilityName(String value) { facilityName = value; }
    public String getClinicianName() { return clinicianName; }
    public void setClinicianName(String value) { clinicianName = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime value) { deletedAt = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
