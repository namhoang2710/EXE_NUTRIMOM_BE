package vn.nutrimom.consultation.service;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.dto.PageResponse;
import vn.nutrimom.consultation.dto.RequestDtos.AdminConsultationResponse;
import vn.nutrimom.consultation.dto.RequestDtos.SlotInfo;
import vn.nutrimom.consultation.dto.ReviewDtos.ReviewResponse;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ConsultationReviewRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/** Admin xem danh sách tiếp nhận (mặc định đã hoàn thành) kèm đánh giá đầy đủ. */
@Service
public class AdminConsultationService {
    private final ConsultationRequestRepository requests;
    private final ConsultationReviewRepository reviews;
    private final ExpertProfileRepository experts;
    private final AvailabilitySlotRepository slots;
    private final UserRepository users;

    public AdminConsultationService(ConsultationRequestRepository requests,
                                    ConsultationReviewRepository reviews,
                                    ExpertProfileRepository experts,
                                    AvailabilitySlotRepository slots,
                                    UserRepository users) {
        this.requests = requests;
        this.reviews = reviews;
        this.experts = experts;
        this.slots = slots;
        this.users = users;
    }

    /**
     * @param status lọc trạng thái; null → mặc định COMPLETED.
     * @param q      tìm theo tên user hoặc tên chuyên gia (không phân biệt hoa thường).
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminConsultationResponse> list(ConsultationStatus status, String q,
                                                        int page, int pageSize) {
        ConsultationStatus effective = status == null ? ConsultationStatus.COMPLETED : status;
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        List<AdminConsultationResponse> all = requests.findByStatusOrderByCreatedAtDesc(effective).stream()
                .map(this::toResponse)
                .filter(response -> matches(response, needle))
                .toList();
        return PageResponse.of(all, page, pageSize);
    }

    private static boolean matches(AdminConsultationResponse response, String needle) {
        if (needle == null) {
            return true;
        }
        return contains(response.userDisplayName(), needle) || contains(response.expertName(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private AdminConsultationResponse toResponse(ConsultationRequestEntity entity) {
        String userName = users.findById(entity.getUserId())
                .map(user -> user.getDisplayName()).orElse(null);
        String expertName = entity.getExpertUserId() == null ? null
                : experts.findById(entity.getExpertUserId())
                        .map(ExpertProfileEntity::getFullName).orElse(null);
        SlotInfo slot = entity.getSlotId() == null ? null
                : slots.findById(entity.getSlotId())
                        .map(s -> new SlotInfo(s.getId(), s.getSlotDate(), s.getStartTime(), s.getEndTime()))
                        .orElse(null);
        ReviewResponse review = reviews.findByRequestId(entity.getId())
                .map(ConsultationReviewService::toResponse)
                .orElse(null);
        return new AdminConsultationResponse(entity.getId(), entity.getUserId(), userName,
                entity.getExpertUserId(), expertName, entity.getSpecialty(), entity.getAssignmentType(),
                entity.getStatus(), slot, entity.getCompletedAt(), entity.getCreatedAt(), review);
    }
}
