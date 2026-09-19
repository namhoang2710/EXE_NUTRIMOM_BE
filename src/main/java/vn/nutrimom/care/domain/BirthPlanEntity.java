package vn.nutrimom.care.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "birth_plans", schema = "app")
public class BirthPlanEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "pregnancy_id", nullable = false, unique = true, length = 36)
    private String pregnancyId;

    @Column(name = "companion", length = 255)
    private String companion;

    @Column(name = "preferred_facility", length = 255)
    private String preferredFacility;

    @Column(name = "pain_management_note", columnDefinition = "nvarchar(max)")
    private String painManagementNote;

    @Column(name = "newborn_care_note", columnDefinition = "nvarchar(max)")
    private String newbornCareNote;

    @Column(name = "free_text_note", columnDefinition = "nvarchar(max)")
    private String freeTextNote;

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
    public String getPregnancyId() { return pregnancyId; }
    public void setPregnancyId(String value) { pregnancyId = value; }
    public String getCompanion() { return companion; }
    public void setCompanion(String value) { companion = value; }
    public String getPreferredFacility() { return preferredFacility; }
    public void setPreferredFacility(String value) { preferredFacility = value; }
    public String getPainManagementNote() { return painManagementNote; }
    public void setPainManagementNote(String value) { painManagementNote = value; }
    public String getNewbornCareNote() { return newbornCareNote; }
    public void setNewbornCareNote(String value) { newbornCareNote = value; }
    public String getFreeTextNote() { return freeTextNote; }
    public void setFreeTextNote(String value) { freeTextNote = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
