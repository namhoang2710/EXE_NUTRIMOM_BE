package vn.nutrimom.consultation.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationReviewEntity;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.dto.ReviewDtos.CreateReviewRequest;
import vn.nutrimom.consultation.dto.ReviewDtos.ReviewResponse;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ConsultationReviewRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/** User đánh giá sau khi tư vấn xong; điểm trung bình cập nhật lại vào hồ sơ chuyên gia. */
@Service
public class ConsultationReviewService {
    private final ConsultationReviewRepository reviews;
    private final ConsultationRequestRepository requests;
    private final ExpertProfileRepository experts;
    private final AccessGuard guard;

    public ConsultationReviewService(ConsultationReviewRepository reviews,
                                     ConsultationRequestRepository requests,
                                     ExpertProfileRepository experts,
                                     AccessGuard guard) {
        this.reviews = reviews;
        this.requests = requests;
        this.experts = experts;
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
        return toResponse(review);
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> listForExpert(String expertUserId) {
        return reviews.findByExpertUserIdOrderByCreatedAtDesc(expertUserId).stream()
                .map(ConsultationReviewService::toResponse)
                .toList();
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

    static ReviewResponse toResponse(ConsultationReviewEntity review) {
        return new ReviewResponse(review.getId(), review.getRequestId(), review.getUserId(),
                review.getExpertUserId(), review.getRating(), review.getComment(), review.getCreatedAt());
    }
}
