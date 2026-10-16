package vn.nutrimom.nutrition.service;

import vn.nutrimom.nutrition.domain.RecommendationReason;

/**
 * Một lý do kèm mẩu dữ liệu đã sinh ra nó.
 *
 * <p>{@code detail} là thứ ép BR15 thành sự thật kỹ thuật: lý do chỉ dựng được khi nơi phát hiện
 * nó cầm sẵn tên nguyên liệu, số phút, tên chế độ ăn — tức là đã đọc từ dữ liệu thật. Không có
 * đường nào tạo ra một lý do mà không đi qua chỗ dữ liệu của nó.</p>
 *
 * @param detail chỗ trống của mẫu câu; null cho lý do không có chỗ trống
 */
public record ReasonNote(RecommendationReason reason, String detail) {

    public static ReasonNote of(RecommendationReason reason) {
        return new ReasonNote(reason, null);
    }

    public static ReasonNote of(RecommendationReason reason, String detail) {
        return new ReasonNote(reason, detail);
    }

    public String message() {
        return reason.message(detail);
    }

    public String code() {
        return reason.name();
    }
}
