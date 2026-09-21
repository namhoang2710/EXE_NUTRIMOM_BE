package vn.nutrimom.consultation.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.consultation.domain.AssignmentType;
import vn.nutrimom.consultation.domain.AvailabilitySlotEntity;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.domain.SlotStatus;
import vn.nutrimom.consultation.dto.RequestDtos.AcceptConsultationRequest;
import vn.nutrimom.consultation.dto.RequestDtos.ConsultationRequestResponse;
import vn.nutrimom.consultation.dto.RequestDtos.CreateConsultationRequest;
import vn.nutrimom.consultation.dto.RequestDtos.SlotInfo;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ConsultationReviewRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/** Tạo/theo dõi yêu cầu tư vấn (user) và tiếp nhận/hoàn thành (chuyên gia). */
@Service
public class ConsultationRequestService {
    private final ConsultationRequestRepository requests;
    private final AvailabilitySlotRepository slots;
    private final ExpertProfileRepository experts;
    private final ConsultationReviewRepository reviews;
    private final AccessGuard guard;

    public ConsultationRequestService(ConsultationRequestRepository requests,
                                      AvailabilitySlotRepository slots,
                                      ExpertProfileRepository experts,
                                      ConsultationReviewRepository reviews,
                                      AccessGuard guard) {
        this.requests = requests;
        this.slots = slots;
        this.experts = experts;
        this.reviews = reviews;
        this.guard = guard;
    }

    // ----- User -----

    @Transactional
    public ConsultationRequestResponse create(String userId, CreateConsultationRequest request) {
        ConsultationRequestEntity entity = new ConsultationRequestEntity();
        entity.setUserId(userId);
        entity.setAssignmentType(request.assignmentType());
        entity.setNote(request.note());

        if (request.assignmentType() == AssignmentType.DIRECT) {
            createDirect(entity, request);
        } else {
            createRandom(entity, request);
        }
        requests.saveAndFlush(entity);
        return toResponse(entity, userId);
    }

    private void createDirect(ConsultationRequestEntity entity, CreateConsultationRequest request) {
        if (request.expertUserId() == null || request.expertUserId().isBlank()
                || request.slotId() == null || request.slotId().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Đặt lịch trực tiếp cần chọn chuyên gia và khung giờ.");
        }
        ExpertProfileEntity expert = experts
                .findByUserIdAndStatus(request.expertUserId(), ExpertStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy chuyên gia."));
        AvailabilitySlotEntity slot = slots.findByIdForUpdate(request.slotId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                        "Khung giờ không tồn tại."));
        if (!slot.getExpertUserId().equals(expert.getUserId()) || slot.getStatus() != SlotStatus.OPEN) {
            throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                    "Khung giờ đã được đặt. Vui lòng chọn khung khác.");
        }
        slot.setStatus(SlotStatus.BOOKED);
        slots.saveAndFlush(slot);

        entity.setExpertUserId(expert.getUserId());
        entity.setSpecialty(expert.getSpecialty());
        entity.setSlotId(slot.getId());
        entity.setStatus(ConsultationStatus.PENDING_CONSULTATION);
    }

    private void createRandom(ConsultationRequestEntity entity, CreateConsultationRequest request) {
        if (request.specialty() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Chọn ngẫu nhiên cần chọn chuyên khoa.");
        }
        entity.setSpecialty(request.specialty());
        entity.setStatus(ConsultationStatus.PENDING_EXPERT);
    }

    @Transactional(readOnly = true)
    public List<ConsultationRequestResponse> listOwn(String userId) {
        return requests.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(entity -> toResponse(entity, userId))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConsultationRequestResponse detail(String userId, String requestId) {
        ConsultationRequestEntity entity = guard.requireOwned(
                requests.findByIdAndUserId(requestId, userId), "Không tìm thấy yêu cầu tư vấn.");
        return toResponse(entity, userId);
    }

    @Transactional
    public ConsultationRequestResponse cancel(String userId, String requestId) {
        ConsultationRequestEntity entity = guard.requireOwned(
                requests.findByIdAndUserId(requestId, userId), "Không tìm thấy yêu cầu tư vấn.");
        if (entity.getStatus() == ConsultationStatus.COMPLETED
                || entity.getStatus() == ConsultationStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_CONSULTATION_STATE,
                    "Yêu cầu này không thể hủy.");
        }
        releaseSlot(entity.getSlotId());
        entity.setSlotId(null);
        entity.setStatus(ConsultationStatus.CANCELLED);
        requests.saveAndFlush(entity);
        return toResponse(entity, userId);
    }

    // ----- Expert -----

    @Transactional(readOnly = true)
    public List<ConsultationRequestResponse> listAssigned(String expertUserId) {
        requireExpert(expertUserId);
        return requests.findByExpertUserIdAndStatusOrderByCreatedAtDesc(
                        expertUserId, ConsultationStatus.PENDING_CONSULTATION).stream()
                .map(entity -> toResponse(entity, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConsultationRequestResponse> listPool(String expertUserId) {
        ExpertProfileEntity expert = requireExpert(expertUserId);
        return requests.findBySpecialtyAndAssignmentTypeAndStatusOrderByCreatedAtAsc(
                        expert.getSpecialty(), AssignmentType.RANDOM, ConsultationStatus.PENDING_EXPERT)
                .stream()
                .map(entity -> toResponse(entity, null))
                .toList();
    }

    @Transactional
    public ConsultationRequestResponse accept(String expertUserId, String requestId,
                                              AcceptConsultationRequest body) {
        ExpertProfileEntity expert = requireExpert(expertUserId);
        ConsultationRequestEntity entity = requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu tư vấn."));
        if (entity.getAssignmentType() != AssignmentType.RANDOM
                || entity.getStatus() != ConsultationStatus.PENDING_EXPERT) {
            throw new BusinessException(ErrorCode.REQUEST_ALREADY_CLAIMED,
                    "Yêu cầu đã được tiếp nhận hoặc không ở trạng thái chờ chuyên gia.");
        }
        if (entity.getSpecialty() != expert.getSpecialty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Yêu cầu này thuộc chuyên khoa khác.");
        }
        AvailabilitySlotEntity slot = slots.findByIdForUpdate(body.slotId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                        "Khung giờ không tồn tại."));
        if (!slot.getExpertUserId().equals(expertUserId) || slot.getStatus() != SlotStatus.OPEN) {
            throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                    "Khung giờ đã được đặt. Vui lòng chọn khung khác.");
        }
        slot.setStatus(SlotStatus.BOOKED);
        slots.saveAndFlush(slot);

        entity.setExpertUserId(expertUserId);
        entity.setSlotId(slot.getId());
        entity.setStatus(ConsultationStatus.PENDING_CONSULTATION);
        requests.saveAndFlush(entity);
        return toResponse(entity, null);
    }

    @Transactional
    public ConsultationRequestResponse complete(String expertUserId, String requestId) {
        requireExpert(expertUserId);
        ConsultationRequestEntity entity = requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu tư vấn."));
        if (!expertUserId.equals(entity.getExpertUserId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy yêu cầu tư vấn.");
        }
        if (entity.getStatus() != ConsultationStatus.PENDING_CONSULTATION) {
            throw new BusinessException(ErrorCode.INVALID_CONSULTATION_STATE,
                    "Chỉ hoàn thành được yêu cầu đang chờ tư vấn.");
        }
        entity.setStatus(ConsultationStatus.COMPLETED);
        entity.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        requests.saveAndFlush(entity);
        return toResponse(entity, null);
    }

    // ----- Helpers -----

    private void releaseSlot(String slotId) {
        if (slotId == null) {
            return;
        }
        slots.findByIdForUpdate(slotId).ifPresent(slot -> {
            slot.setStatus(SlotStatus.OPEN);
            slots.saveAndFlush(slot);
        });
    }

    private ExpertProfileEntity requireExpert(String expertUserId) {
        return experts.findById(expertUserId).orElseThrow(() ->
                new BusinessException(ErrorCode.FORBIDDEN, "Tài khoản của bạn chưa phải hồ sơ chuyên gia."));
    }

    /**
     * @param viewerUserId user đang xem để tính cờ canReview; null khi người xem là chuyên gia.
     */
    private ConsultationRequestResponse toResponse(ConsultationRequestEntity entity, String viewerUserId) {
        String expertName = entity.getExpertUserId() == null ? null
                : experts.findById(entity.getExpertUserId())
                        .map(ExpertProfileEntity::getFullName).orElse(null);
        SlotInfo slot = entity.getSlotId() == null ? null
                : slots.findById(entity.getSlotId())
                        .map(s -> new SlotInfo(s.getId(), s.getSlotDate(), s.getStartTime(), s.getEndTime()))
                        .orElse(null);
        boolean reviewed = reviews.existsByRequestId(entity.getId());
        boolean canReview = viewerUserId != null
                && viewerUserId.equals(entity.getUserId())
                && entity.getStatus() == ConsultationStatus.COMPLETED
                && !reviewed;
        return new ConsultationRequestResponse(entity.getId(), entity.getUserId(),
                entity.getExpertUserId(), expertName, entity.getSpecialty(), entity.getAssignmentType(),
                entity.getStatus(), slot, entity.getNote(), reviewed, canReview,
                entity.getCompletedAt(), entity.getVersion(), entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
