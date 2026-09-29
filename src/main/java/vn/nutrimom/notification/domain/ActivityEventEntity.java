package vn.nutrimom.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Một việc đã xảy ra, hiển thị trên activity feed (spec mục 18 "GET /activity-feed").
 *
 * <p>Khác notification ở chỗ activity không có người nhận và không có trạng thái đã đọc: nó thuộc về
 * {@code subjectUserId} (chủ thai kỳ) và có thể được nhóm gia đình xem chung khi
 * {@code visibility = FAMILY} và {@code pregnancyId} khớp thai kỳ mà họ được chia sẻ.</p>
 *
 * <p><strong>Không thêm cột free-text vào bảng này.</strong> {@code title} phải là chuỗi đã render
 * sẵn từ template an toàn (vd "Chuyên gia đã tiếp nhận yêu cầu tư vấn của bạn"), tuyệt đối không
 * chèn nội dung hồ sơ y tế, câu hỏi tư vấn hay tin nhắn vào — spec mục 18 yêu cầu feed "không làm rò
 * dữ liệu medical qua message preview".</p>
 */
@Entity
@Table(name = "activity_events", schema = "app")
public class ActivityEventEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    /** Chủ của feed — thường là mẹ bầu, người mà hoạt động này nói về. */
    @Column(name = "subject_user_id", nullable = false, length = 36)
    private String subjectUserId;

    /** Người thực hiện hành động (chuyên gia, admin, thành viên gia đình); null nếu là hệ thống. */
    @Column(name = "actor_user_id", length = 36)
    private String actorUserId;

    /** Thai kỳ liên quan; null thì hoạt động không chia sẻ được cho nhóm gia đình. */
    @Column(name = "pregnancy_id", length = 36)
    private String pregnancyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private ActivityType type;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private ActivityVisibility visibility;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Cắt về micro giây vì {@code createdAt} là một nửa khoá cursor — xem {@code NotificationEntity}. */
    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public String getId() { return id; }
    public String getSubjectUserId() { return subjectUserId; }
    public void setSubjectUserId(String value) { subjectUserId = value; }
    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String value) { actorUserId = value; }
    public String getPregnancyId() { return pregnancyId; }
    public void setPregnancyId(String value) { pregnancyId = value; }
    public ActivityType getType() { return type; }
    public void setType(ActivityType value) { type = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public ActivityVisibility getVisibility() { return visibility; }
    public void setVisibility(ActivityVisibility value) { visibility = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
