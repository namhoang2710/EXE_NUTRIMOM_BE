package vn.nutrimom.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "community_reports", schema = "app", uniqueConstraints = @UniqueConstraint(
        name = "ux_community_reports_post_reporter", columnNames = {"post_id", "reporter_id"}))
public class CommunityReport {
    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "post_id", length = 36, nullable = false, updatable = false)
    private String postId;

    @Column(name = "reporter_id", length = 36, nullable = false, updatable = false)
    private String reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 30, nullable = false)
    private ReportReason reason;

    @Column(name = "note", length = 2000)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ReportStatus status;

    @Column(name = "handled_by", length = 36)
    private String handledBy;

    @Column(name = "handled_at")
    private OffsetDateTime handledAt;

    @Column(name = "resolution_note", length = 2000)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = CommunityClock.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = CommunityClock.now();
    }

    public String getId() { return id; }
    public String getPostId() { return postId; }
    public void setPostId(String value) { postId = value; }
    public String getReporterId() { return reporterId; }
    public void setReporterId(String value) { reporterId = value; }
    public ReportReason getReason() { return reason; }
    public void setReason(ReportReason value) { reason = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public ReportStatus getStatus() { return status; }
    public void setStatus(ReportStatus value) { status = value; }
    public String getHandledBy() { return handledBy; }
    public void setHandledBy(String value) { handledBy = value; }
    public OffsetDateTime getHandledAt() { return handledAt; }
    public void setHandledAt(OffsetDateTime value) { handledAt = value; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String value) { resolutionNote = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
