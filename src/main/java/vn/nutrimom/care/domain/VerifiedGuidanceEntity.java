package vn.nutrimom.care.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "verified_guidance", schema = "app")
public class VerifiedGuidanceEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "week")
    private Integer week;

    @Column(name = "topic", length = 100)
    private String topic;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "summary", nullable = false, columnDefinition = "nvarchar(max)")
    private String summary;

    @Column(name = "source_name", nullable = false, length = 255)
    private String sourceName;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "reviewer", nullable = false, length = 255)
    private String reviewer;

    @Column(name = "reviewed_at", nullable = false)
    private OffsetDateTime reviewedAt;

    @Column(name = "next_review_at")
    private OffsetDateTime nextReviewAt;

    @Column(name = "evidence_level", length = 50)
    private String evidenceLevel;

    @Column(name = "disclaimer", nullable = false, columnDefinition = "nvarchar(max)")
    private String disclaimer;

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
    public Integer getWeek() { return week; }
    public void setWeek(Integer value) { week = value; }
    public String getTopic() { return topic; }
    public void setTopic(String value) { topic = value; }
    public String getLocale() { return locale; }
    public void setLocale(String value) { locale = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String value) { sourceName = value; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String value) { sourceUrl = value; }
    public String getReviewer() { return reviewer; }
    public void setReviewer(String value) { reviewer = value; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(OffsetDateTime value) { reviewedAt = value; }
    public OffsetDateTime getNextReviewAt() { return nextReviewAt; }
    public void setNextReviewAt(OffsetDateTime value) { nextReviewAt = value; }
    public String getEvidenceLevel() { return evidenceLevel; }
    public void setEvidenceLevel(String value) { evidenceLevel = value; }
    public String getDisclaimer() { return disclaimer; }
    public void setDisclaimer(String value) { disclaimer = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
