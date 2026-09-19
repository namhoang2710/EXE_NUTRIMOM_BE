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

    @Column(name = "review_status", nullable = false, length = 20)
    private String reviewStatus = "UNREVIEWED";

    @Column(name = "reviewed_by", length = 255)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "next_review_at")
    private OffsetDateTime nextReviewAt;

    @Column(name = "content_version", nullable = false)
    private int contentVersion = 1;

    @Column(name = "baby_length_cm_min", precision = 6, scale = 2)
    private java.math.BigDecimal babyLengthCmMin;

    @Column(name = "baby_length_cm_max", precision = 6, scale = 2)
    private java.math.BigDecimal babyLengthCmMax;

    @Column(name = "baby_weight_g_min")
    private Integer babyWeightGMin;

    @Column(name = "baby_weight_g_max")
    private Integer babyWeightGMax;

    @Column(name = "comparison_label", length = 200)
    private String comparisonLabel;

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
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String value) { reviewStatus = value; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String value) { reviewedBy = value; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(OffsetDateTime value) { reviewedAt = value; }
    public OffsetDateTime getNextReviewAt() { return nextReviewAt; }
    public void setNextReviewAt(OffsetDateTime value) { nextReviewAt = value; }
    public int getContentVersion() { return contentVersion; }
    public void setContentVersion(int value) { contentVersion = value; }
    public java.math.BigDecimal getBabyLengthCmMin() { return babyLengthCmMin; }
    public void setBabyLengthCmMin(java.math.BigDecimal value) { babyLengthCmMin = value; }
    public java.math.BigDecimal getBabyLengthCmMax() { return babyLengthCmMax; }
    public void setBabyLengthCmMax(java.math.BigDecimal value) { babyLengthCmMax = value; }
    public Integer getBabyWeightGMin() { return babyWeightGMin; }
    public void setBabyWeightGMin(Integer value) { babyWeightGMin = value; }
    public Integer getBabyWeightGMax() { return babyWeightGMax; }
    public void setBabyWeightGMax(Integer value) { babyWeightGMax = value; }
    public String getComparisonLabel() { return comparisonLabel; }
    public void setComparisonLabel(String value) { comparisonLabel = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
