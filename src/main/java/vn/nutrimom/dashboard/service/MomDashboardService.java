package vn.nutrimom.dashboard.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.dashboard.domain.DashboardBlock;
import vn.nutrimom.dashboard.dto.BabySummaryResponse;
import vn.nutrimom.dashboard.dto.MomDashboardResponse;
import vn.nutrimom.dashboard.dto.PregnancySummaryResponse;
import vn.nutrimom.dashboard.dto.ProfileSummaryResponse;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;
import vn.nutrimom.pregnancy.dto.PregnancyResponse;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;
import vn.nutrimom.pregnancy.repository.PregnancyWeekContentRepository;
import vn.nutrimom.pregnancy.service.PregnancyService;
import vn.nutrimom.user.dto.UserProfileResponse;
import vn.nutrimom.user.service.UserProfileService;

@Service
public class MomDashboardService {
    private static final Logger log = LoggerFactory.getLogger(MomDashboardService.class);

    private final UserProfileService profiles;
    private final PregnancyService pregnancyService;
    private final PregnancyRepository pregnancies;
    private final PregnancyWeekContentRepository weekContents;

    public MomDashboardService(UserProfileService profiles,
                               PregnancyService pregnancyService,
                               PregnancyRepository pregnancies,
                               PregnancyWeekContentRepository weekContents) {
        this.profiles = profiles;
        this.pregnancyService = pregnancyService;
        this.pregnancies = pregnancies;
        this.weekContents = weekContents;
    }

    @Transactional(readOnly = true)
    public MomDashboardResponse getDashboard(String userId) {
        if (!pregnancies.existsByOwnerUserIdAndStatus(userId, PregnancyStatus.ACTIVE)) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "ACTIVE_PREGNANCY_NOT_FOUND",
                    "An active pregnancy is required for the mom dashboard.");
        }

        UserProfileResponse profile = profiles.getProfile(userId);
        PregnancyResponse pregnancy = pregnancyService.getCurrent(userId);
        return new MomDashboardResponse(
                new ProfileSummaryResponse(
                        profile.id(), profile.displayName(), profile.salutation(), profile.role()),
                new PregnancySummaryResponse(
                        pregnancy.id(), pregnancy.status(), pregnancy.gestationalWeek(),
                        pregnancy.gestationalDay(), pregnancy.trimester(),
                        pregnancy.estimatedDueDate(), pregnancy.daysUntilDue(),
                        pregnancy.careFacilityName()),
                loadBabySummary(pregnancy.gestationalWeek()),
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                0);
    }

    private BabySummaryResponse loadBabySummary(long gestationalWeek) {
        if (gestationalWeek < 0 || gestationalWeek > 42) {
            return null;
        }
        try {
            return weekContents.findById((int) gestationalWeek)
                    .map(content -> new BabySummaryResponse(
                            content.getWeek(), content.getTitle(), content.getSummary(),
                            content.getBabyDevelopment(), content.getDisclaimer()))
                    .orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Optional dashboard block is unavailable: {}",
                    DashboardBlock.BABY_SUMMARY);
            return null;
        }
    }
}
