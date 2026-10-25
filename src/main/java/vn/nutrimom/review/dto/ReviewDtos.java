package vn.nutrimom.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/** DTO của đánh giá web. JSON snake_case theo cấu hình chung; trường null bị bỏ khỏi JSON. */
public final class ReviewDtos {
    private ReviewDtos() { }

    // ---------- request ----------

    /** Tạo hoặc sửa đánh giá của chính mình. {@code version} bắt buộc khi sửa (khoá lạc quan). */
    public record UpsertReviewRequest(
            @NotNull @Min(1) @Max(5) Integer rating,
            @NotNull String content,
            Long version) { }

    public record ReplyRequest(@NotNull String content, @NotNull Long version) { }

    public record HideRequest(@NotBlank @Size(max = 255) String reason, @NotNull Long version) { }

    public record VersionRequest(@NotNull Long version) { }

    // ---------- công khai ----------

    /** Tác giả trên trang công khai: chỉ tên đã che, không id, không avatar, không liên lạc. */
    public record PublicAuthor(String displayName) { }

    public record Reply(String content, OffsetDateTime repliedAt, OffsetDateTime updatedAt) { }

    public record PublicReview(String id, int rating, String content, PublicAuthor author,
                               OffsetDateTime createdAt, OffsetDateTime editedAt, Reply reply) { }

    /** {@code byRating} luôn đủ khoá "1".."5" (0 khi chưa có), {@code average} làm tròn 1 chữ số. */
    public record ReviewSummary(BigDecimal average, long total, Map<String, Long> byRating) { }

    // ---------- của tôi ----------

    public record MyReview(String id, int rating, String content, String status, String hiddenReason,
                           Reply reply, long version, OffsetDateTime createdAt, OffsetDateTime editedAt) { }

    // ---------- admin ----------

    public record AdminAuthor(String id, String displayName, String emailMasked, String phoneMasked,
                              String accountStatus) { }

    public record AdminReview(String id, int rating, String content, String status, String hiddenReason,
                              OffsetDateTime hiddenAt, AdminAuthor author, Reply reply, String repliedBy,
                              boolean editedAfterReply, OffsetDateTime editedAt, OffsetDateTime deletedAt,
                              long version, OffsetDateTime createdAt) { }

    public record PendingCount(long count) { }
}
