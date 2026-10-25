package vn.nutrimom.review.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.service.CommunityCursor;
import vn.nutrimom.community.service.CommunityText;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.service.NotificationService;
import vn.nutrimom.review.domain.WebReviewEntity;
import vn.nutrimom.review.domain.WebReviewStatus;
import vn.nutrimom.review.dto.ReviewDtos.AdminAuthor;
import vn.nutrimom.review.dto.ReviewDtos.AdminReview;
import vn.nutrimom.review.dto.ReviewDtos.HideRequest;
import vn.nutrimom.review.dto.ReviewDtos.PendingCount;
import vn.nutrimom.review.dto.ReviewDtos.ReplyRequest;
import vn.nutrimom.review.repository.WebReviewRepository;

/**
 * Phía admin của đánh giá web: xem mọi trạng thái, trả lời (hiện công khai dưới đánh giá), ẩn/hiện lại.
 *
 * <p>Thông báo cho người viết chỉ gửi ở lần trả lời ĐẦU và khi bị ẩn: sửa câu trả lời không bắn thêm
 * thông báo. Nội dung thông báo không nhắc lại đánh giá vì có thể hiện trên màn hình khoá.</p>
 */
@Service
public class AdminWebReviewService {
    private static final String DEEP_LINK = "nutrimom://reviews/me";
    private static final String SOURCE_TYPE = "WEB_REVIEW";

    private final WebReviewRepository reviews;
    private final UserRepository users;
    private final NotificationService notifications;

    public AdminWebReviewService(WebReviewRepository reviews, UserRepository users,
                                 NotificationService notifications) {
        this.reviews = reviews;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public CursorPage<AdminReview> page(WebReviewStatus status, Integer rating, Boolean replied, String q,
                                        String cursor, Integer limit) {
        int size = WebReviewService.pageSize(limit);
        Short ratingFilter = rating == null ? null : rating.shortValue();
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "rating phải từ 1 đến 5.",
                    Map.of("rating", "rating phải từ 1 đến 5."));
        }
        String search = q == null || q.isBlank() ? null
                : "%" + q.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        CommunityCursor position = CommunityCursor.descending(cursor);
        List<WebReviewEntity> rows = reviews.findAdminPage(status, ratingFilter, replied, search,
                position.at(), position.id(), PageRequest.of(0, size + 1));
        var window = CommunityCursor.window(rows, size, WebReviewEntity::getCreatedAt, WebReviewEntity::getId);
        Map<String, UserEntity> authors = users.findAllById(window.items().stream()
                        .map(WebReviewEntity::getAuthorId).distinct().toList()).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        return new CursorPage<>(window.items().stream()
                .map(review -> toAdmin(review, authors.get(review.getAuthorId()))).toList(),
                window.nextCursor(), window.hasMore());
    }

    @Transactional(readOnly = true)
    public AdminReview get(String id) {
        WebReviewEntity review = load(id);
        return toAdmin(review, users.findById(review.getAuthorId()).orElse(null));
    }

    @Transactional
    public AdminReview reply(String adminId, String id, ReplyRequest request) {
        WebReviewEntity review = loadEditable(id);
        WebReviewViews.requireVersion(request.version(), review);
        String content = WebReviewViews.text(request.content(), 2, WebReviewService.CONTENT_MAX, "content");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean first = !review.hasReply();
        review.setReplyContent(content);
        review.setReplyBy(adminId);
        if (first) review.setRepliedAt(now);
        else review.setReplyUpdatedAt(now);
        // Admin vừa trả lời trên nội dung hiện tại, nên cờ "sửa sau khi trả lời" hết ý nghĩa.
        review.setEditedAfterReply(false);
        reviews.saveAndFlush(review);
        if (first) {
            notifications.publish(review.getAuthorId(), NotificationType.SYSTEM,
                    "NutriMom đã trả lời đánh giá của bạn",
                    "Cảm ơn bạn đã góp ý. Mở ứng dụng để xem câu trả lời.", DEEP_LINK, SOURCE_TYPE, review.getId());
        }
        return get(review.getId());
    }

    @Transactional
    public AdminReview deleteReply(String id, long version) {
        WebReviewEntity review = loadEditable(id);
        WebReviewViews.requireVersion(version, review);
        review.setReplyContent(null);
        review.setReplyBy(null);
        review.setRepliedAt(null);
        review.setReplyUpdatedAt(null);
        review.setEditedAfterReply(false);
        reviews.saveAndFlush(review);
        return get(review.getId());
    }

    @Transactional
    public AdminReview hide(String adminId, String id, HideRequest request) {
        WebReviewEntity review = loadEditable(id);
        WebReviewViews.requireVersion(request.version(), review);
        String reason = CommunityText.required(request.reason(), 255, "reason");
        if (review.getStatus() != WebReviewStatus.HIDDEN) {
            review.setStatus(WebReviewStatus.HIDDEN);
            review.setHiddenReason(reason);
            review.setHiddenBy(adminId);
            review.setHiddenAt(OffsetDateTime.now(ZoneOffset.UTC));
            reviews.saveAndFlush(review);
            notifications.publish(review.getAuthorId(), NotificationType.SYSTEM,
                    "Đánh giá của bạn đã bị ẩn",
                    "Đánh giá không còn hiển thị công khai. Mở ứng dụng để xem lý do.", DEEP_LINK, SOURCE_TYPE,
                    review.getId());
        }
        return get(review.getId());
    }

    @Transactional
    public AdminReview unhide(String id, long version) {
        WebReviewEntity review = loadEditable(id);
        WebReviewViews.requireVersion(version, review);
        if (review.getStatus() == WebReviewStatus.HIDDEN) {
            review.setStatus(WebReviewStatus.PUBLISHED);
            review.setHiddenReason(null);
            review.setHiddenBy(null);
            review.setHiddenAt(null);
            reviews.saveAndFlush(review);
        }
        return get(review.getId());
    }

    @Transactional(readOnly = true)
    public PendingCount pendingCount() {
        return new PendingCount(reviews.countByStatusAndReplyContentIsNull(WebReviewStatus.PUBLISHED));
    }

    private WebReviewEntity load(String id) {
        return reviews.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.WEB_REVIEW_NOT_FOUND));
    }

    private WebReviewEntity loadEditable(String id) {
        WebReviewEntity review = load(id);
        if (review.getStatus() == WebReviewStatus.DELETED) {
            throw new BusinessException(ErrorCode.WEB_REVIEW_DELETED);
        }
        return review;
    }

    private static AdminReview toAdmin(WebReviewEntity review, UserEntity author) {
        AdminAuthor adminAuthor = author == null ? null : new AdminAuthor(author.getId(), author.getDisplayName(),
                maskEmail(author.getEmail()), maskPhone(author.getPhone()), author.getStatus().name());
        return new AdminReview(review.getId(), review.getRating(), review.getContent(), review.getStatus().name(),
                review.getHiddenReason(), review.getHiddenAt(), adminAuthor, WebReviewViews.reply(review),
                review.getReplyBy(), review.isEditedAfterReply(), review.getEditedAt(), review.getDeletedAt(),
                review.getVersion(), review.getCreatedAt());
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return phone == null ? null : "***";
        return "***" + phone.substring(phone.length() - 3);
    }
}
