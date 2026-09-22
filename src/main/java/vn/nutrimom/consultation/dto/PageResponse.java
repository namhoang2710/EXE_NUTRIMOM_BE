package vn.nutrimom.consultation.dto;

import java.util.List;

/** Envelope phân trang đơn giản (offset-based, page bắt đầu từ 1) cho các danh sách có thể phình to. */
public record PageResponse<T>(
        List<T> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {

    /** Cắt trang từ danh sách đã lọc/sắp xếp sẵn. */
    public static <T> PageResponse<T> of(List<T> all, int page, int pageSize) {
        int total = all.size();
        int totalPages = (int) Math.ceil((double) total / pageSize);
        int fromIndex = Math.min((long) (page - 1) * pageSize >= total
                ? total : (page - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        return new PageResponse<>(List.copyOf(all.subList(fromIndex, toIndex)),
                page, pageSize, total, totalPages);
    }
}
