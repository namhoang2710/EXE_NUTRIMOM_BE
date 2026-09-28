package vn.nutrimom.consultation.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.consultation.dto.ExpertDtos.ExpertDetailResponse;
import vn.nutrimom.consultation.dto.ExpertDtos.ExpertSummaryResponse;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/**
 * Tìm/xem chuyên gia (dành cho user đã đăng nhập). Lịch trống của chuyên gia do
 * {@link ExpertScheduleService} phụ trách.
 */
@Service
public class ExpertDirectoryService {
    private final ExpertProfileRepository experts;

    public ExpertDirectoryService(ExpertProfileRepository experts) {
        this.experts = experts;
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

}
