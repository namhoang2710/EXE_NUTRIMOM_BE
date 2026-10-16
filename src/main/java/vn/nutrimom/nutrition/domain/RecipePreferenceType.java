package vn.nutrimom.nutrition.domain;

/**
 * Thái độ chủ động của người dùng với một món cụ thể ({@code app.user_recipe_preferences}).
 *
 * <p><strong>Không có hạn sử dụng</strong> (QĐ-03). Phản hồi có hạn sau bữa ăn là chuyện khác và
 * thuộc {@code app.meal_feedbacks} ở nhánh 8; trộn hai thứ lại thì "tôi không muốn thấy món này
 * nữa" sẽ âm thầm sống lại sau vài tuần.</p>
 */
public enum RecipePreferenceType {

    /** Món quen, món thích. Nâng điểm ở bước chấm điểm, <strong>không</strong> bỏ qua bước lọc. */
    FAVORITE,

    /**
     * Nút "Không muốn gợi ý món này".
     *
     * <p>Đây <strong>không phải</strong> khai báo dị ứng và không bao giờ được hiểu thành dị ứng:
     * người dùng chặn món vì không hợp khẩu vị cũng dùng đúng giá trị này. Dị ứng ở
     * {@code app.user_allergens}.</p>
     */
    BLOCKED
}
