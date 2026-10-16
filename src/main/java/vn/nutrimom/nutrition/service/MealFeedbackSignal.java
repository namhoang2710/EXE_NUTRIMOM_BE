package vn.nutrimom.nutrition.service;

/**
 * Phản hồi gần nhất <strong>còn hiệu lực</strong> của người dùng cho một món.
 *
 * <p>Đây là <strong>chỗ nối cho nhánh 8</strong>. {@code app.meal_feedbacks} chưa tồn tại, nên
 * nhánh 4 luôn truyền null và điểm P chỉ chạy trên phần sở thích chủ động. Công thức vẫn được
 * hiện thực đầy đủ ở {@link RecipeRanker} và kiểm bằng test, để nhánh 8 chỉ phải cắm nguồn dữ
 * liệu vào chứ không phải sửa lại cách tính.</p>
 *
 * <p><strong>Hạn sử dụng đã được xử lý trước khi tới đây.</strong> {@code HARD_NOW} có hạn bảy
 * ngày (QĐ-03); nơi gọi chỉ truyền vào phản hồi còn hiệu lực. Lớp chấm điểm không biết ngày tháng
 * và không nên biết.</p>
 *
 * <p>Chỉ áp <strong>một</strong> phản hồi gần nhất cho mỗi món. Các sở thích khác — yêu thích,
 * nguyên liệu không thích — vẫn cộng trừ riêng.</p>
 */
public enum MealFeedbackSignal {

    /** Ngon, ăn được. */
    TASTY(10),

    /** Không thích. */
    DISLIKE(-25),

    /** Khó ăn lúc này — trạng thái <em>tạm thời</em> của thai kỳ, không phải không thích vĩnh viễn. */
    HARD_NOW(-20);

    private final int delta;

    MealFeedbackSignal(int delta) {
        this.delta = delta;
    }

    /** Mức cộng trừ vào điểm P. */
    public int delta() {
        return delta;
    }
}
