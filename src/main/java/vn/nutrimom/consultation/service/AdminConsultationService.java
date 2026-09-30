package vn.nutrimom.consultation.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.consultation.domain.AvailabilitySlotEntity;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationReviewEntity;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.dto.PageResponse;
import vn.nutrimom.consultation.dto.RequestDtos.AdminConsultationResponse;
import vn.nutrimom.consultation.dto.RequestDtos.SlotInfo;
import vn.nutrimom.consultation.dto.ReviewDtos.ReviewResponse;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ConsultationReviewRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/** Read-only admin view of consultations that experts have completed. */
@Service
public class AdminConsultationService {
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt")
            .and(Sort.by(Sort.Direction.ASC, "id"));

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

    @Transactional(readOnly = true)
    public PageResponse<AdminConsultationResponse> list(String q, int page, int pageSize) {
        String search = q == null || q.isBlank() ? "" : q.trim();
        Page<ConsultationRequestEntity> result = requests.findCompletedForAdmin(
                search, PageRequest.of(page - 1, pageSize, NEWEST_FIRST));
        if (result.isEmpty()) {
            return new PageResponse<>(List.of(), page, pageSize,
                    result.getTotalElements(), result.getTotalPages());
        }

        List<ConsultationRequestEntity> entities = result.getContent();
        Set<String> userIds = entities.stream()
                .map(ConsultationRequestEntity::getUserId)
                .collect(Collectors.toSet());
        Set<String> expertIds = entities.stream()
                .map(ConsultationRequestEntity::getExpertUserId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Set<String> slotIds = entities.stream()
                .map(ConsultationRequestEntity::getSlotId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Set<String> requestIds = entities.stream()
                .map(ConsultationRequestEntity::getId)
                .collect(Collectors.toSet());

        Map<String, String> userNames = users.findDisplayNamesByIdIn(userIds).stream()
                .collect(Collectors.toMap(
                        UserRepository.UserDisplayNameView::getId,
                        UserRepository.UserDisplayNameView::getDisplayName));
        Map<String, ExpertProfileEntity> expertsById = experts.findAllById(expertIds).stream()
                .collect(Collectors.toMap(ExpertProfileEntity::getUserId, Function.identity()));
        Map<String, AvailabilitySlotEntity> slotsById = slots.findAllById(slotIds).stream()
                .collect(Collectors.toMap(AvailabilitySlotEntity::getId, Function.identity()));
        Map<String, ConsultationReviewEntity> reviewsByRequestId = reviews
                .findByRequestIdIn(requestIds).stream()
                .collect(Collectors.toMap(ConsultationReviewEntity::getRequestId, Function.identity()));

        List<AdminConsultationResponse> items = entities.stream()
                .map(entity -> toResponse(entity, userNames, expertsById, slotsById,
                        reviewsByRequestId))
                .toList();
        return new PageResponse<>(items, page, pageSize,
                result.getTotalElements(), result.getTotalPages());
    }

    private AdminConsultationResponse toResponse(
            ConsultationRequestEntity entity,
            Map<String, String> userNames,
            Map<String, ExpertProfileEntity> expertsById,
            Map<String, AvailabilitySlotEntity> slotsById,
            Map<String, ConsultationReviewEntity> reviewsByRequestId) {
        String userName = userNames.get(entity.getUserId());
        ExpertProfileEntity expert = expertsById.get(entity.getExpertUserId());
        String expertName = expert == null ? null : expert.getFullName();
        AvailabilitySlotEntity slotEntity = slotsById.get(entity.getSlotId());
        SlotInfo slot = slotEntity == null ? null : new SlotInfo(
                slotEntity.getId(), slotEntity.getSlotDate(),
                slotEntity.getStartTime(), slotEntity.getEndTime());
        ConsultationReviewEntity reviewEntity = reviewsByRequestId.get(entity.getId());
        ReviewResponse review = reviewEntity == null ? null
                : ConsultationReviewService.toResponse(reviewEntity, userName);
        return new AdminConsultationResponse(entity.getId(), entity.getUserId(), userName,
                entity.getExpertUserId(), expertName, entity.getSpecialty(), entity.getAssignmentType(),
                entity.getStatus(), slot, entity.getCompletedAt(), entity.getCreatedAt(), review);
    }
}
