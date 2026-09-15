package vn.nutrimom.pregnancy.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.pregnancy.domain.PregnancyWeekContentEntity;
import vn.nutrimom.pregnancy.dto.PregnancyWeekContentResponse;
import vn.nutrimom.pregnancy.repository.PregnancyWeekContentRepository;

@Service
public class PregnancyWeekContentService {
    private final PregnancyWeekContentRepository contents;

    public PregnancyWeekContentService(PregnancyWeekContentRepository contents) {
        this.contents = contents;
    }

    @Transactional(readOnly = true)
    public PregnancyWeekContentResponse getWeek(int week) {
        PregnancyWeekContentEntity content = contents.findById(week)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Pregnancy content for this week was not found."));
        return new PregnancyWeekContentResponse(
                content.getWeek(), content.getTitle(), content.getSummary(),
                content.getBabyDevelopment(), content.getMotherChanges(),
                content.getCareTips(), content.getWarningSigns(), content.getSources(),
                content.getDisclaimer(), content.getCreatedAt(), content.getUpdatedAt());
    }
}
