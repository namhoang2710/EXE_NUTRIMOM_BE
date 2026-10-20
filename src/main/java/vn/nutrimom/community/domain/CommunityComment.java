package vn.nutrimom.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "community_comments", schema = "app")
public class CommunityComment {
    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "post_id", length = 36, nullable = false, updatable = false)
    private String postId;

    @Column(name = "author_id", length = 36, nullable = false, updatable = false)
    private String authorId;

    @Column(name = "content", length = 4000, nullable = false)
    private String content;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "deleted_by", length = 36)
    private String deletedBy;

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
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String value) { authorId = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime value) { deletedAt = value; }
    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String value) { deletedBy = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
