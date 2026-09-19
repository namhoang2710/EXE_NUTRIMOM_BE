package vn.nutrimom.pregnancy.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.pregnancy.domain.PregnancyWeekContentEntity;
import vn.nutrimom.pregnancy.dto.PregnancyBabyContentResponse;
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
        if (!"REVIEWED".equalsIgnoreCase(content.getReviewStatus())) {
            throw new BusinessException(ErrorCode.CONTENT_NOT_REVIEWED,
                    "Pregnancy content for this week is not publicly available yet.");
        }
        PregnancyBabyContentResponse baby = content.getBabyLengthCmMin() == null
                && content.getBabyLengthCmMax() == null
                && content.getBabyWeightGMin() == null
                && content.getBabyWeightGMax() == null
                && content.getComparisonLabel() == null
                ? null
                : new PregnancyBabyContentResponse(
                        content.getBabyLengthCmMin() == null || content.getBabyLengthCmMax() == null
                                ? null : java.util.List.of(content.getBabyLengthCmMin(), content.getBabyLengthCmMax()),
                        content.getBabyWeightGMin() == null || content.getBabyWeightGMax() == null
                                ? null : java.util.List.of(content.getBabyWeightGMin(), content.getBabyWeightGMax()),
                        content.getComparisonLabel());
        return new PregnancyWeekContentResponse(
                content.getWeek(), content.getTitle(), content.getSummary(),
                content.getBabyDevelopment(), content.getMotherChanges(),
                content.getCareTips(), content.getWarningSigns(), content.getSources(),
                content.getDisclaimer(), content.getCreatedAt(), content.getUpdatedAt(),
                baby, content.getSummary(), java.util.List.of(content.getMotherChanges()),
                java.util.List.of(content.getCareTips()), content.getReviewedBy(),
                content.getReviewedAt(), content.getNextReviewAt(), content.getContentVersion());
    }
}
