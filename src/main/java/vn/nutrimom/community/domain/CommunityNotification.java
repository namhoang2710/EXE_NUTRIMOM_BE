package vn.nutrimom.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

/** Thông báo riêng của cộng đồng; ghi bằng INSERT ... ON CONFLICT (dedup_key) DO NOTHING. */
@Entity
@Table(name = "community_notifications", schema = "app", uniqueConstraints = @UniqueConstraint(
        name = "ux_community_notifications_dedup", columnNames = "dedup_key"))
public class CommunityNotification {
    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "recipient_id", length = 36, nullable = false)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false)
    private CommunityNotificationType type;

    @Column(name = "post_id", length = 36, nullable = false)
    private String postId;

    @Column(name = "actor_id", length = 36)
    private String actorId;

    @Column(name = "comment_id", length = 36)
    private String commentId;

    @Column(name = "dedup_key", length = 200, nullable = false)
    private String dedupKey;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public String getRecipientId() { return recipientId; }
    public CommunityNotificationType getType() { return type; }
    public String getPostId() { return postId; }
    public String getActorId() { return actorId; }
    public String getCommentId() { return commentId; }
    public OffsetDateTime getReadAt() { return readAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
