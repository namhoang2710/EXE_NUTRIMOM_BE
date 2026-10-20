package vn.nutrimom.common.api;

import java.util.List;

/**
 * Envelope phân trang offset (page bắt đầu từ 1) — bản chuẩn dùng chung.
 *
 * <p>Record thuần, <strong>cố ý không có factory cắt trang trong RAM</strong>: bản cũ
 * {@code consultation.dto.PageResponse#of} load hết danh sách rồi mới cắt, chỉ chịu được khi dữ
 * liệu nhỏ. Người gọi phân trang ở tầng truy vấn (Spring Data {@code Pageable}) rồi dựng record này
 * ở service, nên {@code common.api} không phải kéo {@code org.springframework.data} vào — giữ gói
 * này sạch dependency đúng như {@link CursorPage}.</p>
 *
 * <p>{@code consultation.dto.PageResponse} và {@code admin.dto.AdminOperationsDtos.Page} có trước
 * và mang <em>cùng một</em> shape JSON, nên FE chỉ thấy một hợp đồng; gom chúng về đây là việc của
 * một nhánh dọn dẹp riêng, không phải nhánh này.</p>
 *
 * <p>Dùng khi tổng số trang có ý nghĩa với người đọc (bảng có số trang). Feed cuộn vô hạn dùng
 * {@link CursorPage} để không phải đếm toàn bảng mỗi lần.</p>
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {

    public PageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Dựng từ một trang đã lấy ở tầng truy vấn. */
    public static <T> PageResponse<T> of(List<T> items, int page, int pageSize, long totalItems) {
        return new PageResponse<>(items, page, pageSize, totalItems,
                (int) Math.ceil((double) totalItems / pageSize));
    }
}
