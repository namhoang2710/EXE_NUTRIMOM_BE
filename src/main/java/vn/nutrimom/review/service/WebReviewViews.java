package vn.nutrimom.review.service;

import java.util.Map;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.service.CommunityText;
import vn.nutrimom.review.domain.WebReviewEntity;
import vn.nutrimom.review.dto.ReviewDtos.MyReview;
import vn.nutrimom.review.dto.ReviewDtos.Reply;

/** Phần dùng chung giữa phía người dùng và phía admin: chuyển entity sang DTO, kiểm văn bản và version. */
final class WebReviewViews {
    private WebReviewViews() { }

    static Reply reply(WebReviewEntity review) {
        return review.hasReply()
                ? new Reply(review.getReplyContent(), review.getRepliedAt(), review.getReplyUpdatedAt())
                : null;
    }

    static MyReview mine(WebReviewEntity review) {
        return new MyReview(review.getId(), review.getRating(), review.getContent(), review.getStatus().name(),
                review.getHiddenReason(), reply(review), review.getVersion(), review.getCreatedAt(),
                review.getEditedAt());
    }

    /**
     * Văn bản người dùng/admin nhập: dùng chung bộ làm sạch của Cộng đồng (bỏ ký tự vô hình, gộp dòng trống,
     * đếm theo code point), rồi kiểm độ dài tối thiểu. Lưu và trả về dạng chữ thuần, FE không render HTML.
     */
    static String text(String raw, int min, int max, String field) {
        String value = CommunityText.required(raw, max, field);
        if (value.codePointCount(0, value.length()) < min) {
            String message = field + " tối thiểu " + min + " ký tự";
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, message, Map.of(field, message));
        }
        return value;
    }

    static void requireVersion(Long requested, WebReviewEntity review) {
        if (requested == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Cần gửi version khi sửa đánh giá.",
                    Map.of("version", "Cần gửi version khi sửa đánh giá."));
        }
        if (requested != review.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }
}
