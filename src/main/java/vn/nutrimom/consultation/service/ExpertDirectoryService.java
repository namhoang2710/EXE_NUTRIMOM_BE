package vn.nutrimom.consultation.service;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.consultation.domain.AvailabilitySlotEntity;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.consultation.dto.ExpertDtos.ExpertDetailResponse;
import vn.nutrimom.consultation.dto.ExpertDtos.ExpertSummaryResponse;
import vn.nutrimom.consultation.dto.SlotDtos.SlotResponse;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/** Tìm/xem chuyên gia và xem khung giờ trống của họ (dành cho user đã đăng nhập). */
@Service
public class ExpertDirectoryService {
    private final ExpertProfileRepository experts;
    private final AvailabilitySlotRepository slots;

    public ExpertDirectoryService(ExpertProfileRepository experts, AvailabilitySlotRepository slots) {
        this.experts = experts;
        this.slots = slots;
    }

    @Transactional(readOnly = true)
    public List<ExpertSummaryResponse> list(Specialty specialty) {
        List<ExpertProfileEntity> found = specialty == null
                ? experts.findByStatusOrderByFullNameAsc(ExpertStatus.ACTIVE)
                : experts.findByStatusAndSpecialtyOrderByFullNameAsc(ExpertStatus.ACTIVE, specialty);
        return found.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public ExpertDetailResponse detail(String expertUserId) {
        ExpertProfileEntity profile = requireActiveExpert(expertUserId);
        return new ExpertDetailResponse(profile.getUserId(), profile.getFullName(),
                profile.getSpecialty(), profile.getTitle(), profile.getWorkplace(),
                profile.getYearsOfExperience(), profile.getBio(), profile.getAvatarUrl(),
                profile.getAverageRating(), profile.getRatingCount());
    }

    @Transactional(readOnly = true)
    public List<SlotResponse> slotsForDate(String expertUserId, LocalDate date) {
        requireActiveExpert(expertUserId);
        return slots.findByExpertUserIdAndSlotDateOrderByStartTimeAsc(expertUserId, date).stream()
                .map(ExpertDirectoryService::toSlotResponse)
                .toList();
    }

    private ExpertProfileEntity requireActiveExpert(String expertUserId) {
        return experts.findByUserIdAndStatus(expertUserId, ExpertStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy chuyên gia."));
    }

    private ExpertSummaryResponse toSummary(ExpertProfileEntity profile) {
        return new ExpertSummaryResponse(profile.getUserId(), profile.getFullName(),
                profile.getSpecialty(), profile.getTitle(), profile.getWorkplace(),
                profile.getYearsOfExperience(), profile.getAvatarUrl(),
                profile.getAverageRating(), profile.getRatingCount());
    }

    static SlotResponse toSlotResponse(AvailabilitySlotEntity slot) {
        return new SlotResponse(slot.getId(), slot.getExpertUserId(), slot.getSlotDate(),
                slot.getStartTime(), slot.getEndTime(), slot.getStatus());
    }
}
