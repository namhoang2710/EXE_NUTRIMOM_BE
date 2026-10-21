package vn.nutrimom.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Một bài cộng đồng đã lưu. Khoá chính chặn lưu trùng. */
@Entity
@IdClass(PostUserId.class)
@Table(name = "community_post_saves", schema = "app")
public class CommunityPostSave {
    @Id
    @Column(name = "post_id", length = 36, nullable = false)
    private String postId;

    @Id
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getPostId() { return postId; }
    public String getUserId() { return userId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
