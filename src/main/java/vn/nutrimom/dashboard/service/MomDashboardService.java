package vn.nutrimom.dashboard.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.care.service.CarePlanService;
import vn.nutrimom.dashboard.domain.DashboardBlock;
import vn.nutrimom.dashboard.dto.BabySummaryResponse;
import vn.nutrimom.dashboard.dto.MomDashboardResponse;
import vn.nutrimom.dashboard.dto.PregnancySummaryResponse;
import vn.nutrimom.dashboard.dto.ProfileSummaryResponse;
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
    private final CarePlanService carePlans;

    public MomDashboardService(UserProfileService profiles,
                               PregnancyService pregnancyService,
                               PregnancyRepository pregnancies,
                               PregnancyWeekContentRepository weekContents,
                               CarePlanService carePlans) {
        this.profiles = profiles;
        this.pregnancyService = pregnancyService;
        this.pregnancies = pregnancies;
        this.weekContents = weekContents;
        this.carePlans = carePlans;
    }

    @Transactional(readOnly = true)
    public MomDashboardResponse getDashboard(String userId) {
        return getDashboard(userId, null);
    }

    @Transactional(readOnly = true)
    public MomDashboardResponse getDashboard(String userId, String pregnancyId) {
        UserProfileResponse profile = profiles.getProfile(userId);
        PregnancyResponse pregnancy = pregnancyId == null
                ? pregnancies.findByOwnerUserIdAndStatus(userId,
                        vn.nutrimom.pregnancy.domain.PregnancyStatus.ACTIVE)
                        .map(item -> pregnancyService.getById(userId, item.getId()))
                        .orElse(null)
                : pregnancyService.getById(userId, pregnancyId);
        if (pregnancy == null) {
            return noPregnancyDashboard(profile);
        }
        CarePlanService.CareProgress careProgress = "ACTIVE".equals(pregnancy.status())
                ? carePlans.getProgress(userId, pregnancy.id()) : null;
        return new MomDashboardResponse(
                profileSummary(profile),
                new PregnancySummaryResponse(
                        pregnancy.id(), pregnancy.status(), pregnancy.gestationalWeek(),
                        pregnancy.gestationalDay(), pregnancy.trimester(),
                        pregnancy.estimatedDueDate(), pregnancy.daysUntilDue(),
                        pregnancy.careFacilityName()),
                loadBabySummary(pregnancy.gestationalWeek()),
                null,
                null,
                careProgress,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                0);
    }

    private MomDashboardResponse noPregnancyDashboard(UserProfileResponse profile) {
        return new MomDashboardResponse(
                profileSummary(profile), null, null, null, null, null,
                List.of(), List.of(), List.of(), null, null, 0);
    }

    private ProfileSummaryResponse profileSummary(UserProfileResponse profile) {
        return new ProfileSummaryResponse(
                profile.id(), profile.displayName(), profile.salutation(), profile.role());
    }

    private BabySummaryResponse loadBabySummary(long gestationalWeek) {
        if (gestationalWeek < 0 || gestationalWeek > 42) {
            return null;
        }
        try {
            return weekContents.findById((int) gestationalWeek)
                    .filter(content -> "REVIEWED".equalsIgnoreCase(content.getReviewStatus()))
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
