package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Lựa chọn chủ động của người dùng với một món (bảng {@code app.user_recipe_preferences}, V58).
 *
 * <p><strong>Không có {@code expiresAt}</strong>, dù đặc tả gốc có (QĐ-03). Đặc tả cho cả bảng này
 * lẫn {@code app.meal_feedbacks} một cột hết hạn và cả hai cùng ảnh hưởng điểm {@code P} — cùng
 * một ý niệm nằm ở hai chỗ. Đã tách dứt khoát: bảng này là lựa chọn chủ động, sống đến khi người
 * dùng tự đổi; phản hồi <em>có hạn</em> sau bữa ăn thuộc {@code app.meal_feedbacks} ở nhánh 8.</p>
 *
 * <p>Khoá theo {@code userId} chứ không theo hồ sơ thai kỳ, đúng như đặc tả: "tôi không thích món
 * này" không mất đi khi mẹ bầu bắt đầu một thai kỳ mới.</p>
 *
 * <p>Cả {@link RecipePreferenceType#FAVORITE} lẫn {@link RecipePreferenceType#BLOCKED} đều
 * <strong>không bypass bước lọc</strong> (bất biến 2): món yêu thích vẫn phải qua
 * {@code EligibilityFilter} ở nhánh 4 trước khi được chấm điểm, nếu không thì một món quen có
 * chứa thành phần gây dị ứng sẽ lọt vào thực đơn.</p>
 */
@Entity
@Table(name = "user_recipe_preferences", schema = "app")
@IdClass(UserRecipePreferenceId.class)
public class UserRecipePreferenceEntity {

    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Id
    @Column(name = "recipe_id", nullable = false, length = 64)
    private String recipeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "preference_type", nullable = false, length = 20)
    private RecipePreferenceType preferenceType;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getRecipeId() { return recipeId; }
    public void setRecipeId(String value) { recipeId = value; }
    public RecipePreferenceType getPreferenceType() { return preferenceType; }
    public void setPreferenceType(RecipePreferenceType value) { preferenceType = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
