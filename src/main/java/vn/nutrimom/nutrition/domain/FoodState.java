package vn.nutrimom.nutrition.domain;

/**
 * Trạng thái chế biến của nguyên liệu, dùng cho cả {@code ingredients.food_state} và
 * {@code recipe_ingredients.preparation_state}.
 *
 * <p>Đây là cột mang nghĩa nghiệp vụ chứ không phải nhãn mô tả: danh sách mua sắm (nhánh 7)
 * <strong>không được gộp</strong> hai dòng khác trạng thái — 100g gạo {@link #RAW} và 100g cơm
 * {@link #COOKED} là hai thứ khác nhau; và bộ tính dinh dưỡng (nhánh 2) chỉ áp hệ số giữ lại khi
 * bản ghi dinh dưỡng mô tả nguyên liệu sống.</p>
 */
public enum FoodState {
    RAW,
    COOKED,
    DRIED,
    PROCESSED
}
