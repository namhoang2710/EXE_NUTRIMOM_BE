package vn.nutrimom.consultation.service;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.consultation.domain.AvailabilitySlotEntity;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.SlotStatus;
import vn.nutrimom.consultation.dto.SlotDtos.CreateSlotRequest;
import vn.nutrimom.consultation.dto.SlotDtos.SlotResponse;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/** Chuyên gia tự quản lý khung giờ trống của mình. */
@Service
public class AvailabilityService {
    private final AvailabilitySlotRepository slots;
    private final ExpertProfileRepository experts;
    private final AccessGuard guard;

    public AvailabilityService(AvailabilitySlotRepository slots, ExpertProfileRepository experts,
                               AccessGuard guard) {
        this.slots = slots;
        this.experts = experts;
        this.guard = guard;
    }

    @Transactional
    public SlotResponse create(String expertUserId, CreateSlotRequest request) {
        requireExpert(expertUserId);
        if (!request.endTime().isAfter(request.startTime())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Giờ kết thúc phải sau giờ bắt đầu.");
        }
        AvailabilitySlotEntity slot = new AvailabilitySlotEntity();
        slot.setExpertUserId(expertUserId);
        slot.setSlotDate(request.slotDate());
        slot.setStartTime(request.startTime());
        slot.setEndTime(request.endTime());
        slot.setStatus(SlotStatus.OPEN);
        try {
            slots.saveAndFlush(slot);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                    "Bạn đã có một khung giờ bắt đầu vào thời điểm này.");
        }
        return ExpertDirectoryService.toSlotResponse(slot);
    }

    @Transactional(readOnly = true)
    public List<SlotResponse> listOwn(String expertUserId) {
        requireExpert(expertUserId);
        return slots.findByExpertUserIdAndStatusOrderBySlotDateAscStartTimeAsc(expertUserId, SlotStatus.OPEN)
                .stream()
                .map(ExpertDirectoryService::toSlotResponse)
                .toList();
    }

    @Transactional
    public void delete(String expertUserId, String slotId) {
        AvailabilitySlotEntity slot = guard.requireOwned(
                slots.findByIdAndExpertUserId(slotId, expertUserId), "Không tìm thấy khung giờ.");
        if (slot.getStatus() == SlotStatus.BOOKED) {
            throw new BusinessException(ErrorCode.INVALID_CONSULTATION_STATE,
                    "Không thể xóa khung giờ đã có người đặt.");
        }
        slots.delete(slot);
    }

    private ExpertProfileEntity requireExpert(String expertUserId) {
        return experts.findById(expertUserId).orElseThrow(() ->
                new BusinessException(ErrorCode.FORBIDDEN, "Tài khoản của bạn chưa phải hồ sơ chuyên gia."));
    }
}
