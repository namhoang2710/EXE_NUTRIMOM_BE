package vn.nutrimom.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Ảnh cộng đồng trên R2. Vòng đời: PENDING → ATTACHED → ORPHANED → (job dọn) xoá. */
@Entity
@Table(name = "community_post_images", schema = "app")
public class CommunityPostImage {
    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "post_id", length = 36)
    private String postId;

    @Column(name = "owner_id", length = 36, nullable = false, updatable = false)
    private String ownerId;

    @Column(name = "image_key", length = 200, nullable = false, updatable = false)
    private String imageKey;

    @Column(name = "image_url", length = 2048, nullable = false)
    private String imageUrl;

    @Column(name = "width", nullable = false)
    private int width;

    @Column(name = "height", nullable = false)
    private int height;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ImageStatus status;

    @Column(name = "delete_after")
    private OffsetDateTime deleteAfter;

    @Column(name = "cleanup_attempts", nullable = false)
    private int cleanupAttempts;

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
    public void setId(String value) { id = value; }
    public String getPostId() { return postId; }
    public void setPostId(String value) { postId = value; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String value) { ownerId = value; }
    public String getImageKey() { return imageKey; }
    public void setImageKey(String value) { imageKey = value; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String value) { imageUrl = value; }
    public int getWidth() { return width; }
    public void setWidth(int value) { width = value; }
    public int getHeight() { return height; }
    public void setHeight(int value) { height = value; }
    public int getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(int value) { sizeBytes = value; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int value) { sortOrder = value; }
    public ImageStatus getStatus() { return status; }
    public void setStatus(ImageStatus value) { status = value; }
    public OffsetDateTime getDeleteAfter() { return deleteAfter; }
    public void setDeleteAfter(OffsetDateTime value) { deleteAfter = value; }
    public int getCleanupAttempts() { return cleanupAttempts; }
    public void setCleanupAttempts(int value) { cleanupAttempts = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
