package vn.nutrimom.pregnancy.domain;

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

@Entity
@Table(name = "pregnancies", schema = "app")
public class PregnancyEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PregnancyStatus status;

    @Column(name = "estimated_due_date", nullable = false)
    private LocalDate estimatedDueDate;

    @Column(name = "last_menstrual_period", nullable = false)
    private LocalDate lastMenstrualPeriod;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_source", nullable = false, length = 20)
    private PregnancyCalculationSource calculationSource;

    @Column(name = "care_facility_name", length = 255)
    private String careFacilityName;

    @Column(name = "care_provider_name", length = 255)
    private String careProviderName;

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
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public PregnancyStatus getStatus() { return status; }
    public void setStatus(PregnancyStatus status) { this.status = status; }
    public LocalDate getEstimatedDueDate() { return estimatedDueDate; }
    public void setEstimatedDueDate(LocalDate value) { estimatedDueDate = value; }
    public LocalDate getLastMenstrualPeriod() { return lastMenstrualPeriod; }
    public void setLastMenstrualPeriod(LocalDate value) { lastMenstrualPeriod = value; }
    public PregnancyCalculationSource getCalculationSource() { return calculationSource; }
    public void setCalculationSource(PregnancyCalculationSource value) { calculationSource = value; }
    public String getCareFacilityName() { return careFacilityName; }
    public void setCareFacilityName(String value) { careFacilityName = value; }
    public String getCareProviderName() { return careProviderName; }
    public void setCareProviderName(String value) { careProviderName = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
