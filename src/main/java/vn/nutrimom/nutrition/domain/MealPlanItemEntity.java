package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Một món đã chọn cho một bữa (bảng {@code app.meal_plan_items}, migration V60).
 *
 * <p><strong>Mục kế hoạch không bao giờ tự thành mục nhật ký</strong> (bất biến 3). Đây là phần
 * <em>dự kiến</em>; phần <em>đã ghi</em> sống ở {@code app.diary_entries} của nhánh 8, và tổng "đã
 * ăn" chỉ lấy từ bên đó.</p>
 *
 * <h2>Vì sao lưu snapshot của công thức, không lưu tổng đã tính</h2>
 *
 * <p>{@code nutrientSnapshot} là JSON của {@code NutritionSnapshots.RecipeSnapshot} — nguyên liệu,
 * lượng gram và bảng dinh dưỡng tại thời điểm chọn. Tổng dinh dưỡng được dựng lại bằng chính
 * {@code NutritionCalculator} của nhánh 2 mỗi lần đọc kế hoạch, nên <strong>chỉ có một bộ
 * tính</strong> (bất biến 4) và tổng API trả về không thể lệch với tổng tính từ snapshot các mục.
 * Lưu sẵn con số tổng sẽ tạo ra nguồn sự thật thứ hai.</p>
 *
 * <p>Seed sửa công thức về sau cũng không chạm được vào kế hoạch cũ: snapshot và
 * {@code recipeVersion} giữ nguyên thứ đã dùng (BR17).</p>
 */
@Entity
@Table(name = "meal_plan_items", schema = "app")
public class MealPlanItemEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private MealSlotEntity slot;

    @Column(name = "recipe_id", nullable = false, length = 64)
    private String recipeId;

    @Column(name = "recipe_version", nullable = false)
    private int recipeVersion;

    @Column(name = "servings", nullable = false, precision = 6, scale = 2)
    private BigDecimal servings;

    @Column(name = "locked", nullable = false)
    private boolean locked;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nutrient_snapshot", nullable = false)
    private String nutrientSnapshot;

    /** Chi phí ước tính của mục; null = <strong>chưa quy đổi được giá</strong>, không phải 0 đồng. */
    @Column(name = "cost_snapshot", precision = 12, scale = 2)
    private BigDecimal costSnapshot;

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public MealSlotEntity getSlot() { return slot; }
    public void setSlot(MealSlotEntity value) { slot = value; }
    public String getRecipeId() { return recipeId; }
    public void setRecipeId(String value) { recipeId = value; }
    public int getRecipeVersion() { return recipeVersion; }
    public void setRecipeVersion(int value) { recipeVersion = value; }
    public BigDecimal getServings() { return servings; }
    public void setServings(BigDecimal value) { servings = value; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean value) { locked = value; }
    public String getNutrientSnapshot() { return nutrientSnapshot; }
    public void setNutrientSnapshot(String value) { nutrientSnapshot = value; }
    public BigDecimal getCostSnapshot() { return costSnapshot; }
    public void setCostSnapshot(BigDecimal value) { costSnapshot = value; }
}
