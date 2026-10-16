package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Bản xem trước có thời hạn (bảng {@code app.meal_plan_previews}, migration V60 — QĐ-01).
 *
 * <p><strong>Chưa phải kế hoạch đã lưu.</strong> Nó sống 30 phút rồi hết hạn; lưu lúc đã hết hạn bị
 * từ chối, và {@code profileVersion} còn được kiểm lại lần nữa lúc lưu.</p>
 *
 * <p>{@code requestId} ở đây <strong>là</strong> idempotency key (khác {@code requestId} của nhật ký
 * gợi ý, vốn chỉ là mã tương quan): unique cùng {@code userId}, nên lần gửi thứ hai vì mạng chậm trả
 * lại đúng bản ghi cũ thay vì chạy lại cả lượt tìm kiếm và sinh ra một thực đơn khác.</p>
 */
@Entity
@Table(name = "meal_plan_previews", schema = "app")
public class MealPlanPreviewEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion;

    @Column(name = "rules_version", nullable = false, length = 64)
    private String rulesVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GenerationStatus status;

    /** Nguyên văn body xem trước, gồm cả bản {@link GenerationStatus#NO_MATCH} kèm lý do. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** Kế hoạch đã lưu từ bản xem trước này; null khi chưa lưu — chặn lưu trùng hai lần. */
    @Column(name = "saved_plan_id", length = 36)
    private String savedPlanId;

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public boolean isExpiredAt(OffsetDateTime moment) {
        return !expiresAt.isAfter(moment);
    }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public int getProfileVersion() { return profileVersion; }
    public void setProfileVersion(int value) { profileVersion = value; }
    public String getRulesVersion() { return rulesVersion; }
    public void setRulesVersion(String value) { rulesVersion = value; }
    public GenerationStatus getStatus() { return status; }
    public void setStatus(GenerationStatus value) { status = value; }
    public String getPayload() { return payload; }
    public void setPayload(String value) { payload = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime value) { expiresAt = value; }
    public String getSavedPlanId() { return savedPlanId; }
    public void setSavedPlanId(String value) { savedPlanId = value; }
}
