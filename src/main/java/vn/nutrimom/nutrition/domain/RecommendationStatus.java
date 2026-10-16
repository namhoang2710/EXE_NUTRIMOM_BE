package vn.nutrimom.nutrition.domain;

/**
 * Kết quả của một lượt gợi ý cho một bữa.
 *
 * <p>{@link #NO_MATCH} <strong>không phải lỗi</strong> — nó trả về trong thân HTTP 200 kèm lý do.
 * "Không tìm được món" là kết quả nghiệp vụ hợp lệ của một hồ sơ có nhiều điều kiện bắt buộc, và
 * biến nó thành mã lỗi sẽ đẩy giao diện vào nhánh xử lý sự cố thay vì nhánh mời người dùng nới
 * các ưu tiên <em>mềm</em>.</p>
 *
 * <p>Và chỉ nới ưu tiên mềm: thời gian, ngân sách, món không thích. Không bao giờ nới dị ứng hay
 * chế độ ăn bắt buộc để có kết quả (bất biến 5).</p>
 */
public enum RecommendationStatus {
    READY,
    NO_MATCH
}
