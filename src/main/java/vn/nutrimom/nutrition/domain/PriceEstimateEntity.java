package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Giá <strong>ước tính</strong> của một nguyên liệu, tính bằng VND (bảng
 * {@code app.price_estimates}, migration V57).
 *
 * <p>{@code estimatedAt} bắt buộc (BR18) để màn hình nói được "giá tham khảo tháng 10/2026" thay vì
 * ngụ ý đây là báo giá hiện tại của cửa hàng. Nguyên liệu <em>chưa có</em> dòng giá thì danh sách
 * mua sắm hiển thị "Chưa có giá" và tổng chi phí mang nhãn chưa đầy đủ — không coi là 0 đồng.</p>
 */
@Entity
@Table(name = "price_estimates", schema = "app")
public class PriceEstimateEntity {

    @Id
    @Column(name = "ingredient_id", nullable = false, length = 64)
    private String ingredientId;

    @Column(name = "price_per_base_unit_vnd", nullable = false, precision = 12, scale = 2)
    private BigDecimal pricePerBaseUnitVnd;

    @Column(name = "base_unit", nullable = false, length = 16)
    private String baseUnit;

    @Column(name = "estimated_at", nullable = false)
    private LocalDate estimatedAt;

    @Column(name = "source_note", length = 255)
    private String sourceNote;

    public String getIngredientId() { return ingredientId; }
    public void setIngredientId(String value) { ingredientId = value; }

    public BigDecimal getPricePerBaseUnitVnd() { return pricePerBaseUnitVnd; }
    public void setPricePerBaseUnitVnd(BigDecimal value) { pricePerBaseUnitVnd = value; }

    public String getBaseUnit() { return baseUnit; }
    public void setBaseUnit(String value) { baseUnit = value; }

    public LocalDate getEstimatedAt() { return estimatedAt; }
    public void setEstimatedAt(LocalDate value) { estimatedAt = value; }

    public String getSourceNote() { return sourceNote; }
    public void setSourceNote(String value) { sourceNote = value; }
}
