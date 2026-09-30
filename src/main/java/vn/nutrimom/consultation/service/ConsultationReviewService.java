package vn.nutrimom.consultation.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationReviewEntity;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.dto.PageResponse;
import vn.nutrimom.consultation.dto.ReviewDtos.CreateReviewRequest;
import vn.nutrimom.consultation.dto.ReviewDtos.ReviewResponse;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ConsultationReviewRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/** User đánh giá sau khi tư vấn xong; điểm trung bình cập nhật lại vào hồ sơ chuyên gia. */
@Service
public class ConsultationReviewService {
    private static final String UNKNOWN_USER_DISPLAY_NAME = "Người dùng";

    private final ConsultationReviewRepository reviews;
    private final ConsultationRequestRepository requests;
    private final ExpertProfileRepository experts;
    private final UserRepository users;
    private final AccessGuard guard;

    public ConsultationReviewService(ConsultationReviewRepository reviews,
                                     ConsultationRequestRepository requests,
                                     ExpertProfileRepository experts,
                                     UserRepository users,
                                     AccessGuard guard) {
        this.reviews = reviews;
        this.requests = requests;
        this.experts = experts;
        this.users = users;
        this.guard = guard;
    }

    @Transactional
    public ReviewResponse create(String userId, String requestId, CreateReviewRequest body) {
        ConsultationRequestEntity request = guard.requireOwned(
                requests.findByIdAndUserId(requestId, userId), "Không tìm thấy yêu cầu tư vấn.");
        if (request.getStatus() != ConsultationStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_ALLOWED,
                    "Chỉ có thể đánh giá sau khi buổi tư vấn đã hoàn thành.");
        }
        if (reviews.existsByRequestId(requestId)) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS, "Bạn đã đánh giá buổi tư vấn này rồi.");
        }
        String expertUserId = request.getExpertUserId();
        if (expertUserId == null) {
            throw new BusinessException(ErrorCode.INVALID_CONSULTATION_STATE,
                    "Yêu cầu chưa gắn chuyên gia để đánh giá.");
        }

        ConsultationReviewEntity review = new ConsultationReviewEntity();
        review.setRequestId(requestId);
        review.setUserId(userId);
        review.setExpertUserId(expertUserId);
        review.setRating((short) (int) body.rating());
        review.setComment(body.comment());
        reviews.saveAndFlush(review);

        recomputeExpertRating(expertUserId, body.rating());
        return toResponse(review, displayNameFor(userId));
    }

    /**
     * @param rating     lọc theo số sao (1-5) nếu khác null.
     * @param from       ngày bắt đầu (giờ VN, bao gồm) nếu khác null.
     * @param to         ngày kết thúc (giờ VN, bao gồm) nếu khác null.
     * @param sort       "newest" (mặc định), "rating_desc" hoặc "rating_asc".
     * @param hasComment true → chỉ lấy đánh giá có nhận xét (text).
     */
    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> listForExpert(String expertUserId, Integer rating,
                                                      LocalDate from, LocalDate to, String sort,
                                                      Boolean hasComment, int page, int pageSize) {
        Page<ConsultationReviewEntity> result = reviews.findAll(
                reviewFilter(expertUserId, rating, from, to, hasComment),
                PageRequest.of(page - 1, pageSize, sortFor(sort)));
        Map<String, String> displayNames = displayNamesFor(result.getContent());
        List<ReviewResponse> items = result.getContent().stream()
                .map(review -> toResponse(review, displayNames.get(review.getUserId())))
                .toList();
        return new PageResponse<>(items, page, pageSize,
                result.getTotalElements(), result.getTotalPages());
    }

    private static Specification<ConsultationReviewEntity> reviewFilter(
            String expertUserId, Integer rating, LocalDate from, LocalDate to,
            Boolean hasComment) {
        OffsetDateTime fromInclusive = ConsultationClock.startOfDay(from);
        OffsetDateTime toExclusive = ConsultationClock.startOfNextDay(to);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("expertUserId"), expertUserId));
            if (rating != null) {
                predicates.add(cb.equal(root.get("rating"), rating.shortValue()));
            }
            if (fromInclusive != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromInclusive));
            }
            if (toExclusive != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), toExclusive));
            }
            if (hasComment != null) {
                Predicate hasText = cb.and(
                        cb.isNotNull(root.get("comment")),
                        cb.notEqual(cb.trim(root.get("comment")), ""));
                predicates.add(hasComment ? hasText : cb.not(hasText));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Sort sortFor(String sort) {
        Sort newest = Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.DESC, "id"));
        String normalized = sort == null ? "newest" : sort.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "rating_desc" -> Sort.by(Sort.Direction.DESC, "rating").and(newest);
            case "rating_asc" -> Sort.by(Sort.Direction.ASC, "rating").and(newest);
            default -> newest;
        };
    }

    private Map<String, String> displayNamesFor(List<ConsultationReviewEntity> page) {
        Set<String> userIds = page.stream().map(ConsultationReviewEntity::getUserId)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        users.findDisplayNamesByIdIn(userIds)
                .forEach(user -> names.put(user.getId(), user.getDisplayName()));
        return names;
    }

    private String displayNameFor(String userId) {
        return users.findDisplayNamesByIdIn(Set.of(userId)).stream()
                .findFirst().map(UserRepository.UserDisplayNameView::getDisplayName)
                .orElse(null);
    }

    private void recomputeExpertRating(String expertUserId, int newRating) {
        ExpertProfileEntity expert = experts.findByIdForUpdate(expertUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy chuyên gia."));
        int oldCount = expert.getRatingCount();
        BigDecimal oldSum = expert.getAverageRating().multiply(BigDecimal.valueOf(oldCount));
        int newCount = oldCount + 1;
        BigDecimal newAverage = oldSum.add(BigDecimal.valueOf(newRating))
                .divide(BigDecimal.valueOf(newCount), 2, RoundingMode.HALF_UP);
        expert.setRatingCount(newCount);
        expert.setAverageRating(newAverage);
        experts.saveAndFlush(expert);
    }

    static ReviewResponse toResponse(ConsultationReviewEntity review, String userDisplayName) {
        String safeDisplayName = userDisplayName == null || userDisplayName.isBlank()
                ? UNKNOWN_USER_DISPLAY_NAME : userDisplayName;
        return new ReviewResponse(review.getId(), review.getRequestId(), review.getUserId(),
                safeDisplayName, review.getExpertUserId(), review.getRating(), review.getComment(),
                review.getCreatedAt());
    }
}
