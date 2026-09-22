package vn.nutrimom.consultation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** DTO cho đánh giá chuyên gia sau buổi tư vấn. */
public final class ReviewDtos {
    private ReviewDtos() {
    }

    /** User gửi đánh giá (kiểu đơn hàng): sao 1-5 + nhận xét. */
    public record CreateReviewRequest(
            @NotNull(message = "Thiếu số sao đánh giá.")
            @Min(value = 1, message = "Số sao từ 1 đến 5.")
            @Max(value = 5, message = "Số sao từ 1 đến 5.")
            Integer rating,
            @Size(max = 2000, message = "Nhận xét quá dài.") String comment) {
    }

    /** Đánh giá hiển thị (chuyên gia và admin đều xem được đầy đủ sao + text). */
    public record ReviewResponse(
            String id,
            String requestId,
            String userId,
            String expertUserId,
            short rating,
            String comment,
            OffsetDateTime createdAt) {
    }
}
