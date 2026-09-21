package vn.nutrimom.consultation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Đánh giá của user sau khi yêu cầu tư vấn hoàn thành. Unique {@code request_id}
 * đảm bảo mỗi yêu cầu chỉ được đánh giá một lần.
 */
@Entity
@Table(name = "consultation_reviews", schema = "app")
public class ConsultationReviewEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "expert_user_id", nullable = false, length = 36)
    private String expertUserId;

    @Column(name = "rating", nullable = false)
    private short rating;

    @Column(name = "comment", columnDefinition = "nvarchar(max)")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getExpertUserId() { return expertUserId; }
    public void setExpertUserId(String value) { expertUserId = value; }
    public short getRating() { return rating; }
    public void setRating(short value) { rating = value; }
    public String getComment() { return comment; }
    public void setComment(String value) { comment = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
