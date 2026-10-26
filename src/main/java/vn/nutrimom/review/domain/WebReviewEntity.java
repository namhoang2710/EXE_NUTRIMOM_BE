package vn.nutrimom.review.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "web_reviews", schema = "app")
public class WebReviewEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "author_id", nullable = false, length = 36)
    private String authorId;

    @Column(name = "rating", nullable = false)
    private short rating;

    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WebReviewStatus status = WebReviewStatus.PUBLISHED;

    @Column(name = "hidden_reason", length = 255)
    private String hiddenReason;

    @Column(name = "hidden_by", length = 36)
    private String hiddenBy;

    @Column(name = "hidden_at")
    private OffsetDateTime hiddenAt;

    @Column(name = "reply_content", length = 1000)
    private String replyContent;

    @Column(name = "reply_by", length = 36)
    private String replyBy;

    @Column(name = "replied_at")
    private OffsetDateTime repliedAt;

    @Column(name = "reply_updated_at")
    private OffsetDateTime replyUpdatedAt;

    @Column(name = "edited_after_reply", nullable = false)
    private boolean editedAfterReply;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

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
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public boolean hasReply() { return replyContent != null; }

    public String getId() { return id; }
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }
    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = (short) rating; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public WebReviewStatus getStatus() { return status; }
    public void setStatus(WebReviewStatus status) { this.status = status; }
    public String getHiddenReason() { return hiddenReason; }
    public void setHiddenReason(String hiddenReason) { this.hiddenReason = hiddenReason; }
    public String getHiddenBy() { return hiddenBy; }
    public void setHiddenBy(String hiddenBy) { this.hiddenBy = hiddenBy; }
    public OffsetDateTime getHiddenAt() { return hiddenAt; }
    public void setHiddenAt(OffsetDateTime hiddenAt) { this.hiddenAt = hiddenAt; }
    public String getReplyContent() { return replyContent; }
    public void setReplyContent(String replyContent) { this.replyContent = replyContent; }
    public String getReplyBy() { return replyBy; }
    public void setReplyBy(String replyBy) { this.replyBy = replyBy; }
    public OffsetDateTime getRepliedAt() { return repliedAt; }
    public void setRepliedAt(OffsetDateTime repliedAt) { this.repliedAt = repliedAt; }
    public OffsetDateTime getReplyUpdatedAt() { return replyUpdatedAt; }
    public void setReplyUpdatedAt(OffsetDateTime replyUpdatedAt) { this.replyUpdatedAt = replyUpdatedAt; }
    public boolean isEditedAfterReply() { return editedAfterReply; }
    public void setEditedAfterReply(boolean editedAfterReply) { this.editedAfterReply = editedAfterReply; }
    public OffsetDateTime getEditedAt() { return editedAt; }
    public void setEditedAt(OffsetDateTime editedAt) { this.editedAt = editedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
