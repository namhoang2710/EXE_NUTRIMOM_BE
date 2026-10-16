package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;

/**
 * Một giá trị dinh dưỡng trên 100g phần ăn được của nguyên liệu
 * (bảng {@code app.ingredient_nutrients}, migration V57).
 *
 * <p>{@code amountPer100g} <strong>cố ý cho phép null</strong>. Thiếu dữ liệu khác với bằng 0
 * (BR08): nguyên liệu chưa đo folate không có nghĩa là không chứa folate. Bộ tính dinh dưỡng ở
 * nhánh 2 phải đánh dấu tổng là PARTIAL thay vì cộng thêm 0, và giao diện ghi "chưa đủ dữ liệu"
 * thay vì tô đầy thanh tiến trình.</p>
 */
@Embeddable
public class IngredientNutrientValue {

    @Column(name = "nutrient_code", nullable = false, length = 32)
    private String nutrientCode;

    @Column(name = "amount_per_100g", precision = 12, scale = 4)
    private BigDecimal amountPer100g;

    @Column(name = "unit", nullable = false, length = 16)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NutrientStatus status;

    @Column(name = "source_id", length = 64)
    private String sourceId;

    protected IngredientNutrientValue() {
    }

    public IngredientNutrientValue(String nutrientCode, BigDecimal amountPer100g, String unit,
                                   NutrientStatus status, String sourceId) {
        this.nutrientCode = nutrientCode;
        this.amountPer100g = amountPer100g;
        this.unit = unit;
        this.status = status;
        this.sourceId = sourceId;
    }

    public String getNutrientCode() { return nutrientCode; }

    /** @return null khi chưa có dữ liệu — không bao giờ quy về 0. */
    public BigDecimal getAmountPer100g() { return amountPer100g; }

    public String getUnit() { return unit; }

    public NutrientStatus getStatus() { return status; }

    public String getSourceId() { return sourceId; }
}
