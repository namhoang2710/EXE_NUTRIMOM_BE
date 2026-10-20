package vn.nutrimom.community.domain;

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
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Bài viết cộng đồng.
 *
 * <p>Người dùng được tham chiếu bằng id thay vì {@code @ManyToOne UserEntity}: UserEntity nạp roles
 * kiểu EAGER, join nó vào feed sẽ sinh thêm một câu truy vấn cho mỗi tác giả (N+1).</p>
 *
 * <p>{@code @DynamicUpdate}: like_count/comment_count do câu UPDATE nguyên tử cộng trừ; khi sửa nội
 * dung bài, Hibernate chỉ ghi cột đã đổi nên không ghi đè bộ đếm bằng giá trị cũ đã nạp.</p>
 */
@Entity
@DynamicUpdate
@Table(name = "community_posts", schema = "app")
public class CommunityPost {
    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "author_id", length = 36, nullable = false, updatable = false)
    private String authorId;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "topic", length = 30, nullable = false)
    private PostTopic topic;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PostStatus status;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "moderated_by", length = 36)
    private String moderatedBy;

    @Column(name = "moderated_at")
    private OffsetDateTime moderatedAt;

    @Column(name = "pinned_at")
    private OffsetDateTime pinnedAt;

    @Column(name = "pinned_by", length = 36)
    private String pinnedBy;

    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(name = "comment_count", nullable = false)
    private int commentCount;

    @Column(name = "image_count", nullable = false)
    private int imageCount;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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

    public boolean isDeleted() { return deletedAt != null; }
    public boolean isPublic() { return status == PostStatus.APPROVED && deletedAt == null; }

    public String getId() { return id; }
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String value) { authorId = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public PostTopic getTopic() { return topic; }
    public void setTopic(PostTopic value) { topic = value; }
    public PostStatus getStatus() { return status; }
    public void setStatus(PostStatus value) { status = value; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String value) { rejectReason = value; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime value) { publishedAt = value; }
    public String getModeratedBy() { return moderatedBy; }
    public void setModeratedBy(String value) { moderatedBy = value; }
    public OffsetDateTime getModeratedAt() { return moderatedAt; }
    public void setModeratedAt(OffsetDateTime value) { moderatedAt = value; }
    public OffsetDateTime getPinnedAt() { return pinnedAt; }
    public void setPinnedAt(OffsetDateTime value) { pinnedAt = value; }
    public String getPinnedBy() { return pinnedBy; }
    public void setPinnedBy(String value) { pinnedBy = value; }
    public int getLikeCount() { return likeCount; }
    public int getCommentCount() { return commentCount; }
    public int getImageCount() { return imageCount; }
    public void setImageCount(int value) { imageCount = value; }
    public OffsetDateTime getEditedAt() { return editedAt; }
    public void setEditedAt(OffsetDateTime value) { editedAt = value; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime value) { deletedAt = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
