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

/**
 * Một lần người dùng nói cảm nhận sau bữa (bảng {@code app.meal_feedbacks}, migration V64).
 *
 * <p>Bảng ghi theo kiểu <strong>nhật ký</strong>: mỗi lần chạm là một dòng mới, không cập nhật dòng
 * cũ. Người ta đổi ý theo thời gian, và đè lên dòng cũ sẽ xóa mất chuỗi đổi ý đó. Lúc chấm điểm chỉ
 * lấy <strong>một</strong> phản hồi gần nhất còn hiệu lực (QĐ-03), nên lịch sử dài không làm sai
 * kết quả.</p>
 *
 * <p>Khác {@link UserRecipePreferenceEntity} ở đúng một điểm cốt lõi: bảng kia là lựa chọn
 * <em>chủ động</em> và không có hạn, bảng này là cảm nhận <em>sau bữa</em> và
 * {@link MealFeedbackSignal#HARD_NOW} tự hết sau bảy ngày. Không dòng nào ở đây được biến thành dị
 * ứng — dị ứng do người dùng khai trong hồ sơ và là điều kiện an toàn ở bước lọc.</p>
 */
@Entity
@Table(name = "meal_feedbacks", schema = "app")
public class MealFeedbackEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "recipe_id", nullable = false, length = 64)
    private String recipeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private MealFeedbackSignal type;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Null = không hết hạn; chỉ {@link MealFeedbackSignal#HARD_NOW} mang giá trị (QĐ-03). */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** Phản hồi còn hiệu lực tại một thời điểm. */
    public boolean isValidAt(OffsetDateTime moment) {
        return expiresAt == null || expiresAt.isAfter(moment);
    }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getRecipeId() { return recipeId; }
    public void setRecipeId(String value) { recipeId = value; }
    public MealFeedbackSignal getType() { return type; }
    public void setType(MealFeedbackSignal value) { type = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime value) { expiresAt = value; }
}
