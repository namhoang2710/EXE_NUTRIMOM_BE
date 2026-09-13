package vn.nutrimom.pregnancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "pregnancy_week_contents", schema = "app")
public class PregnancyWeekContentEntity {
    @Id
    @Column(name = "week", nullable = false)
    private Integer week;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "summary", nullable = false, columnDefinition = "nvarchar(max)")
    private String summary;

    @Column(name = "baby_development", nullable = false, columnDefinition = "nvarchar(max)")
    private String babyDevelopment;

    @Column(name = "mother_changes", nullable = false, columnDefinition = "nvarchar(max)")
    private String motherChanges;

    @Column(name = "care_tips", nullable = false, columnDefinition = "nvarchar(max)")
    private String careTips;

    @Column(name = "warning_signs", nullable = false, columnDefinition = "nvarchar(max)")
    private String warningSigns;

    @Column(name = "sources", nullable = false, columnDefinition = "nvarchar(max)")
    private String sources;

    @Column(name = "disclaimer", nullable = false, columnDefinition = "nvarchar(max)")
    private String disclaimer;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public Integer getWeek() { return week; }
    public void setWeek(Integer week) { this.week = week; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getBabyDevelopment() { return babyDevelopment; }
    public void setBabyDevelopment(String value) { babyDevelopment = value; }
    public String getMotherChanges() { return motherChanges; }
    public void setMotherChanges(String value) { motherChanges = value; }
    public String getCareTips() { return careTips; }
    public void setCareTips(String value) { careTips = value; }
    public String getWarningSigns() { return warningSigns; }
    public void setWarningSigns(String value) { warningSigns = value; }
    public String getSources() { return sources; }
    public void setSources(String sources) { this.sources = sources; }
    public String getDisclaimer() { return disclaimer; }
    public void setDisclaimer(String disclaimer) { this.disclaimer = disclaimer; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
