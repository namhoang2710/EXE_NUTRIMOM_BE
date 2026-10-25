package vn.nutrimom.review.service;

import static vn.nutrimom.notification.domain.AdminNotificationEvent.ADMIN_REVIEW_NEW;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.service.CommunityCursor;
import vn.nutrimom.notification.service.AdminNotificationEvents;
import vn.nutrimom.review.domain.WebReviewEntity;
import vn.nutrimom.review.domain.WebReviewStatus;
import vn.nutrimom.review.dto.ReviewDtos.MyReview;
import vn.nutrimom.review.dto.ReviewDtos.PublicAuthor;
import vn.nutrimom.review.dto.ReviewDtos.PublicReview;
import vn.nutrimom.review.dto.ReviewDtos.ReviewSummary;
import vn.nutrimom.review.dto.ReviewDtos.UpsertReviewRequest;
import vn.nutrimom.review.repository.WebReviewRepository;

/**
 * Đánh giá web: trang công khai và đánh giá của chính người dùng.
 *
 * <p>Mỗi người một đánh giá còn hiệu lực. {@link #upsert} khoá dòng user trước khi đọc, nên hai request
 * "tạo" chạy cùng lúc không đẻ ra hai đánh giá; index một phần {@code ux_web_reviews_author_active} của V69
 * là chốt cuối ở database.</p>
 */
@Service
public class WebReviewService {
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;
    static final int CONTENT_MIN = 10;
    static final int CONTENT_MAX = 1000;
    /** Vai trò nội bộ: đánh giá của họ không phải đánh giá của khách hàng. */
    private static final Set<UserRole> STAFF = EnumSet.of(UserRole.ADMIN, UserRole.EXPERT,
            UserRole.CONTENT_EDITOR, UserRole.CONTENT_REVIEWER, UserRole.CONTENT_PUBLISHER);

    private final WebReviewRepository reviews;
    private final UserRepository users;
    private final AdminNotificationEvents adminEvents;

    public WebReviewService(WebReviewRepository reviews, UserRepository users, AdminNotificationEvents adminEvents) {
        this.reviews = reviews;
        this.users = users;
        this.adminEvents = adminEvents;
    }

    @Transactional(readOnly = true)
    public CursorPage<PublicReview> publicPage(Integer rating, String cursor, Integer limit) {
        Short ratingFilter = ratingFilter(rating);
        int size = pageSize(limit);
        CommunityCursor position = CommunityCursor.descending(cursor);
        List<WebReviewEntity> rows = reviews.findPublicPage(ratingFilter, position.at(), position.id(),
                PageRequest.of(0, size + 1));
        var window = CommunityCursor.window(rows, size, WebReviewEntity::getCreatedAt, WebReviewEntity::getId);
        Map<String, UserEntity> authors = usersById(window.items().stream().map(WebReviewEntity::getAuthorId).toList());
        List<PublicReview> items = window.items().stream()
                .map(review -> new PublicReview(review.getId(), review.getRating(), review.getContent(),
                        new PublicAuthor(maskName(authorName(authors.get(review.getAuthorId())))),
                        review.getCreatedAt(), review.getEditedAt(), WebReviewViews.reply(review)))
                .toList();
        return new CursorPage<>(items, window.nextCursor(), window.hasMore());
    }

    @Transactional(readOnly = true)
    public ReviewSummary summary() {
        Map<String, Long> byRating = new LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) byRating.put(String.valueOf(star), 0L);
        long total = 0;
        long points = 0;
        for (Object[] row : reviews.countPublicByRating()) {
            int star = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            byRating.put(String.valueOf(star), count);
            total += count;
            points += star * count;
        }
        BigDecimal average = total == 0 ? null
                : BigDecimal.valueOf(points).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
        return new ReviewSummary(average, total, byRating);
    }

    @Transactional(readOnly = true)
    public MyReview mine(String userId) {
        WebReviewEntity review = reviews.findByAuthorIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WEB_REVIEW_NOT_FOUND));
        return WebReviewViews.mine(review);
    }

    /** Tạo mới (lần đầu) hoặc sửa. Trả về đánh giá kèm cờ {@code created} để controller chọn 201/200. */
    @Transactional
    public Upserted upsert(String userId, UpsertReviewRequest request) {
        UserEntity author = users.findByIdForUpdate(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE));
        if (!author.getRoles().contains(UserRole.USER) || author.getRoles().stream().anyMatch(STAFF::contains)) {
            throw new BusinessException(ErrorCode.WEB_REVIEW_NOT_ALLOWED);
        }
        String content = WebReviewViews.text(request.content(), CONTENT_MIN, CONTENT_MAX, "content");
        int rating = request.rating();

        var existing = reviews.findByAuthorIdAndDeletedAtIsNull(userId);
        if (existing.isEmpty()) {
            WebReviewEntity review = new WebReviewEntity();
            review.setAuthorId(userId);
            review.setRating(rating);
            review.setContent(content);
            review.setStatus(WebReviewStatus.PUBLISHED);
            reviews.saveAndFlush(review);
            adminEvents.record(ADMIN_REVIEW_NEW, review.getId());
            return new Upserted(WebReviewViews.mine(review), true);
        }

        WebReviewEntity review = existing.get();
        if (review.getStatus() == WebReviewStatus.HIDDEN) {
            // Sửa không được dùng để "lách" lệnh ẩn của admin.
            throw new BusinessException(ErrorCode.WEB_REVIEW_HIDDEN);
        }
        WebReviewViews.requireVersion(request.version(), review);
        if (review.getRating() != rating || !review.getContent().equals(content)) {
            review.setRating(rating);
            review.setContent(content);
            review.setEditedAt(OffsetDateTime.now(ZoneOffset.UTC));
            // Câu trả lời cũ có thể không còn khớp nội dung mới: đánh dấu để admin xem lại.
            if (review.hasReply()) review.setEditedAfterReply(true);
            reviews.saveAndFlush(review);
        }
        return new Upserted(WebReviewViews.mine(review), false);
    }

    @Transactional
    public void delete(String userId) {
        WebReviewEntity review = reviews.findByAuthorIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WEB_REVIEW_NOT_FOUND));
        review.setStatus(WebReviewStatus.DELETED);
        review.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        reviews.saveAndFlush(review);
    }

    public record Upserted(MyReview review, boolean created) { }

    private Map<String, UserEntity> usersById(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return users.findAllById(ids).stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    }

    private static String authorName(UserEntity user) {
        return user == null ? null : user.getDisplayName();
    }

    /**
     * Che tên trên trang công khai (người dùng là mẹ bầu, đánh giá có thể nhắc chuyện sức khoẻ):
     * "Nguyễn Thị Lan" → "Lan N.", tên một chữ "Lan" → "L***".
     */
    static String maskName(String displayName) {
        if (displayName == null || displayName.isBlank()) return "Thành viên NutriMom";
        String[] words = displayName.trim().split("\\s+");
        if (words.length == 1) {
            return words[0].substring(0, words[0].offsetByCodePoints(0, 1)) + "***";
        }
        String first = words[0];
        return words[words.length - 1] + " " + first.substring(0, first.offsetByCodePoints(0, 1)).toUpperCase() + ".";
    }

    private static Short ratingFilter(Integer rating) {
        if (rating == null) return null;
        if (rating < 1 || rating > 5) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "rating phải từ 1 đến 5.",
                    Map.of("rating", "rating phải từ 1 đến 5."));
        }
        return rating.shortValue();
    }

    static int pageSize(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit phải từ 1 đến " + MAX_LIMIT + ".",
                    Map.of("limit", "limit phải từ 1 đến " + MAX_LIMIT + "."));
        }
        return limit;
    }
}
