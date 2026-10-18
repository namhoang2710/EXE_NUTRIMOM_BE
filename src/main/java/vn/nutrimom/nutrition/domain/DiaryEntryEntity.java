package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Một món <strong>thực tế đã ăn</strong> (bảng {@code app.diary_entries}, migration V64).
 *
 * <h2>Bất biến 3 — độc lập với kế hoạch</h2>
 *
 * <p>Đây không phải một cách nhìn khác của {@link MealPlanItemEntity}. Mục kế hoạch là khẩu phần
 * <em>dự kiến</em>; dòng này là khẩu phần <em>đã xác nhận</em>, và không có đường nào biến cái thứ
 * nhất thành cái thứ hai: {@code sourcePlanItemId} chỉ ghi lại <strong>nguồn gợi ra</strong> lần
 * ghi này và được phép null, {@code actualServings} được phép khác số phần của mục kế hoạch (TC15),
 * còn xóa kế hoạch chỉ xóa cái nguồn đó chứ không xóa dòng này. Sửa kế hoạch không viết lại nhật
 * ký, và tổng "đã ghi" chỉ lấy từ bảng này.</p>
 *
 * <h2>Hai dạng dòng</h2>
 *
 * <p><strong>Món trong danh mục</strong> mang {@code recipeId}, {@code recipeVersion},
 * {@code recipeSnapshot} và {@code actualServings}. <strong>Món ngoài bộ dữ liệu</strong> chỉ mang
 * {@code customText} và {@code amountText}: hệ thống ghi lại tên và lượng người dùng gõ rồi dừng ở
 * đó. Không có snapshot nghĩa là dòng ấy không đóng góp con số nào vào tổng — đó chính là cách
 * "không tự sinh vi chất từ tên món" được ép ở mức dữ liệu, chứ không phải bằng một câu {@code if}
 * ai đó có thể quên. {@code ck_diary_entries_shape} chặn mọi dạng thứ ba.</p>
 *
 * <h2>Vì sao lưu snapshot, không lưu tổng đã tính</h2>
 *
 * <p>Giống {@link MealPlanItemEntity}: tổng được dựng lại bằng chính {@code NutritionCalculator}
 * mỗi lần đọc, nên chỉ có một bộ tính (bất biến 4). Seed sửa công thức về sau cũng không chạm được
 * vào bữa đã ăn — snapshot và {@code recipeVersion} giữ nguyên thứ đã dùng lúc ghi (BR17, TC21).</p>
 */
@Entity
@Table(name = "diary_entries", schema = "app")
public class DiaryEntryEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /**
     * Ngày theo lịch của người dùng, do client gửi lên.
     *
     * <p>Cố ý không suy từ {@code createdAt}: một bữa tối ghi lúc 0 giờ 15 vẫn thuộc về ngày hôm
     * trước, và máy chủ không biết người dùng đang ở múi giờ nào.</p>
     */
    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 20)
    private MealType mealType;

    /** Null = món ngoài bộ dữ liệu; lúc đó {@code customText} mang tên người dùng gõ. */
    @Column(name = "recipe_id", length = 64)
    private String recipeId;

    @Column(name = "recipe_version")
    private Integer recipeVersion;

    /** JSON của {@code NutritionSnapshots.RecipeSnapshot} tại thời điểm ghi; null ở món ngoài danh mục. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipe_snapshot")
    private String recipeSnapshot;

    /** Số phần <strong>người dùng xác nhận</strong> (BR16); null ở món ngoài danh mục. */
    @Column(name = "actual_servings", precision = 6, scale = 2)
    private BigDecimal actualServings;

    @Column(name = "custom_text", length = 255)
    private String customText;

    /**
     * Lượng đã ăn của món ngoài danh mục, <strong>bằng chữ</strong> ("một bát", "nửa hộp").
     *
     * <p>Để chữ chứ không ép thành số phần: quy "một bát" ra 1,0 phần của một công thức nào đó là
     * bịa ra đúng con số mà nhánh này cấm bịa.</p>
     */
    @Column(name = "amount_text", length = 64)
    private String amountText;

    /**
     * Người dùng tự đánh dấu "nguyên liệu đã đổi đáng kể, đây chỉ là ước tính".
     *
     * <p>Số liệu vẫn tính theo công thức gốc vì không có nguồn nào khác, nhưng tổng của ngày mang
     * {@link CoverageStatus#PARTIAL} và giao diện phải nói ra: coi số của công thức gốc là chính
     * xác cho một món đã đổi nguyên liệu là một lời khẳng định không có cơ sở.</p>
     */
    @Column(name = "estimate_incomplete", nullable = false)
    private boolean estimateIncomplete;

    /** Mục kế hoạch đã gợi ra lần ghi này; null khi người dùng ghi món ngoài thực đơn. */
    @Column(name = "source_plan_item_id", length = 36)
    private String sourcePlanItemId;

    /** Idempotency key, unique cùng {@code userId} (_CONTRACT.md mục 6, TC16). */
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** Dòng có đóng góp số liệu vào tổng hay không — món ngoài danh mục thì không. */
    public boolean hasNutritionData() {
        return recipeSnapshot != null;
    }

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public LocalDate getLocalDate() { return localDate; }
    public void setLocalDate(LocalDate value) { localDate = value; }
    public MealType getMealType() { return mealType; }
    public void setMealType(MealType value) { mealType = value; }
    public String getRecipeId() { return recipeId; }
    public void setRecipeId(String value) { recipeId = value; }
    public Integer getRecipeVersion() { return recipeVersion; }
    public void setRecipeVersion(Integer value) { recipeVersion = value; }
    public String getRecipeSnapshot() { return recipeSnapshot; }
    public void setRecipeSnapshot(String value) { recipeSnapshot = value; }
    public BigDecimal getActualServings() { return actualServings; }
    public void setActualServings(BigDecimal value) { actualServings = value; }
    public String getCustomText() { return customText; }
    public void setCustomText(String value) { customText = value; }
    public String getAmountText() { return amountText; }
    public void setAmountText(String value) { amountText = value; }
    public boolean isEstimateIncomplete() { return estimateIncomplete; }
    public void setEstimateIncomplete(boolean value) { estimateIncomplete = value; }
    public String getSourcePlanItemId() { return sourcePlanItemId; }
    public void setSourcePlanItemId(String value) { sourcePlanItemId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
}
