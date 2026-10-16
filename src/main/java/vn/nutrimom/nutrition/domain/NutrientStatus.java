package vn.nutrimom.nutrition.domain;

/**
 * Chất lượng của một giá trị dinh dưỡng.
 *
 * <p>{@link #MISSING} luôn đi kèm {@code amountPer100g == null}: thiếu dữ liệu <strong>khác</strong>
 * bằng 0 (BR08). Một nguyên liệu chưa đo folate không có nghĩa nó không chứa folate, nên tổng nào
 * chạm vào nó phải mang coverage PARTIAL thay vì cộng thêm 0.</p>
 */
public enum NutrientStatus {
    MEASURED,
    ESTIMATED,
    MISSING
}
