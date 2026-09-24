package vn.nutrimom.care.service;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.care.domain.BirthPlanEntity;
import vn.nutrimom.care.domain.PreparationItemEntity;
import vn.nutrimom.care.domain.VerifiedGuidanceEntity;
import vn.nutrimom.care.dto.BirthPlanResponse;
import vn.nutrimom.care.dto.CarePlanMilestoneResponse;
import vn.nutrimom.care.dto.CarePlanProgressResponse;
import vn.nutrimom.care.dto.CarePlanResponse;
import vn.nutrimom.care.dto.PreparationItemResponse;
import vn.nutrimom.care.dto.PutBirthPlanRequest;
import vn.nutrimom.care.dto.UpdatePreparationItemRequest;
import vn.nutrimom.care.dto.VerifiedGuidanceResponse;
import vn.nutrimom.care.repository.BirthPlanRepository;
import vn.nutrimom.care.repository.PreparationItemRepository;
import vn.nutrimom.care.repository.VerifiedGuidanceRepository;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.pregnancy.domain.PregnancyEntity;
import vn.nutrimom.pregnancy.domain.PregnancyCalculationSource;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;

@Service
public class CarePlanService {
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository users;
    private final PregnancyRepository pregnancies;
    private final VerifiedGuidanceRepository guidance;
    private final PreparationItemRepository preparationItems;
    private final BirthPlanRepository birthPlans;

    public CarePlanService(UserRepository users,
                           PregnancyRepository pregnancies,
                           VerifiedGuidanceRepository guidance,
                           PreparationItemRepository preparationItems,
                           BirthPlanRepository birthPlans) {
        this.users = users;
        this.pregnancies = pregnancies;
        this.guidance = guidance;
        this.preparationItems = preparationItems;
        this.birthPlans = birthPlans;
    }

    @Transactional(readOnly = true)
    public CarePlanResponse getCurrentCarePlan(String userId) {
        PregnancyEntity pregnancy = loadCurrentPregnancy(userId);
        List<PreparationItemEntity> items = preparationItems
                .findByPregnancyIdOrderBySortOrderAsc(pregnancy.getId());
        return new CarePlanResponse(pregnancy.getId(), List.of(), gestationalWeek(pregnancy), careProgress(items));
    }

    @Transactional(readOnly = true)
    public CursorPage<VerifiedGuidanceResponse> getGuidance(Integer week, String topic,
                                                            String locale, String cursor, int limit) {
        int pageSize = normalizeLimit(limit);
        List<VerifiedGuidanceEntity> filtered = guidance.findAllByOrderByWeekAscCreatedAtAscIdAsc()
                .stream()
                .filter(item -> week == null || week.equals(item.getWeek()))
                .filter(item -> topic == null || topic.isBlank()
                        || (item.getTopic() != null && item.getTopic().equalsIgnoreCase(topic.trim())))
                .filter(item -> locale == null || locale.isBlank()
                        || item.getLocale().equalsIgnoreCase(locale.trim()))
                .toList();
        int offset = decodeCursor(cursor, filtered.size());
        int end = Math.min(offset + pageSize, filtered.size());
        List<VerifiedGuidanceResponse> page = filtered.subList(offset, end).stream()
                .map(this::toGuidanceResponse).toList();
        boolean hasMore = end < filtered.size();
        return new CursorPage<>(page, hasMore ? encodeCursor(end) : null, hasMore);
    }

    @Transactional
    public List<PreparationItemResponse> getPreparationItems(String userId) {
        PregnancyEntity pregnancy = loadCurrentPregnancy(userId);
        List<PreparationItemEntity> items = preparationItems
                .findByPregnancyIdOrderBySortOrderAsc(pregnancy.getId());
        if (items.isEmpty()) {
            items = createDefaultPreparationItems(pregnancy.getId());
        }
        return items.stream().map(this::toPreparationResponse).toList();
    }

    @Transactional
    public PreparationItemResponse updatePreparationItem(String userId, String itemId,
                                                         UpdatePreparationItemRequest request) {
        PregnancyEntity pregnancy = loadCurrentPregnancy(userId);
        PreparationItemEntity item = preparationItems.findByIdAndPregnancyId(itemId, pregnancy.getId())
                .orElseThrow(this::notFound);
        if (request.version() != item.getVersion()) {
            throw versionConflict();
        }
        if (request.completed() != null) {
            item.setCompleted(request.completed());
            item.setCompletedAt(request.completed() ? OffsetDateTime.now(ZoneOffset.UTC) : null);
        }
        preparationItems.saveAndFlush(item);
        return toPreparationResponse(item);
    }

    @Transactional
    public BirthPlanResponse getCurrentBirthPlan(String userId) {
        PregnancyEntity pregnancy = loadCurrentPregnancy(userId);
        BirthPlanEntity plan = birthPlans.findByPregnancyId(pregnancy.getId())
                .orElseGet(() -> createBirthPlan(pregnancy.getId()));
        return toBirthPlanResponse(plan);
    }

    @Transactional
    public BirthPlanResponse putCurrentBirthPlan(String userId, PutBirthPlanRequest request) {
        PregnancyEntity pregnancy = loadCurrentPregnancy(userId);
        BirthPlanEntity plan = birthPlans.findByPregnancyId(pregnancy.getId())
                .orElseGet(() -> createBirthPlan(pregnancy.getId()));
        if (request.version() != plan.getVersion()) {
            throw versionConflict();
        }
        plan.setCompanion(trimToNull(request.companion()));
        plan.setPreferredFacility(trimToNull(request.preferredFacility()));
        plan.setPainManagementNote(trimToNull(request.painManagementNote()));
        plan.setNewbornCareNote(trimToNull(request.newbornCareNote()));
        plan.setFreeTextNote(trimToNull(request.freeTextNote()));
        birthPlans.saveAndFlush(plan);
        return toBirthPlanResponse(plan);
    }

    @Transactional(readOnly = true)
    public CareProgress getProgress(String userId, String pregnancyId) {
        PregnancyEntity pregnancy = pregnancies.findByIdAndOwnerUserId(pregnancyId, userId)
                .filter(item -> item.getStatus() == PregnancyStatus.ACTIVE)
                .orElseThrow(this::notFound);
        List<PreparationItemEntity> items = preparationItems
                .findByPregnancyIdOrderBySortOrderAsc(pregnancy.getId());
        if (items.isEmpty()) return null;
        return new CareProgress(items.size(), (int) items.stream().filter(PreparationItemEntity::isCompleted).count(),
                progress(items));
    }

    private PregnancyEntity loadCurrentPregnancy(String userId) {
        users.findById(userId).filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED", "The authenticated account is unavailable."));
        return pregnancies.findByOwnerUserIdAndStatus(userId, PregnancyStatus.ACTIVE)
                .orElseThrow(this::notFound);
    }

    private List<PreparationItemEntity> createDefaultPreparationItems(String pregnancyId) {
        List<PreparationItemEntity> defaults = List.of(
                newPreparationItem(pregnancyId, "CARE_VISIT", "Prepare questions for the next prenatal visit", 10),
                newPreparationItem(pregnancyId, "CARE_PLAN", "Discuss the care plan with your care provider", 20),
                newPreparationItem(pregnancyId, "BIRTH_PREFERENCES", "Record birth preferences to discuss with your care team", 30));
        return preparationItems.saveAllAndFlush(defaults);
    }

    private PreparationItemEntity newPreparationItem(String pregnancyId, String groupCode,
                                                      String title, int sortOrder) {
        PreparationItemEntity item = new PreparationItemEntity();
        item.setPregnancyId(pregnancyId);
        item.setGroupCode(groupCode);
        item.setTitle(title);
        item.setSortOrder(sortOrder);
        return item;
    }

    private BirthPlanEntity createBirthPlan(String pregnancyId) {
        BirthPlanEntity plan = new BirthPlanEntity();
        plan.setPregnancyId(pregnancyId);
        return birthPlans.saveAndFlush(plan);
    }

    private VerifiedGuidanceResponse toGuidanceResponse(VerifiedGuidanceEntity item) {
        return new VerifiedGuidanceResponse(item.getId(), item.getWeek(), item.getTopic(), item.getLocale(),
                item.getTitle(), item.getSummary(), item.getSourceName(), item.getSourceUrl(),
                item.getReviewer(), item.getReviewedAt(), item.getNextReviewAt(), item.getEvidenceLevel(),
                item.getDisclaimer());
    }

    private PreparationItemResponse toPreparationResponse(PreparationItemEntity item) {
        return new PreparationItemResponse(item.getId(), item.getGroupCode(), item.getTitle(),
                item.isCompleted(), item.getCompletedAt(), item.getSortOrder(), item.getVersion());
    }

    private BirthPlanResponse toBirthPlanResponse(BirthPlanEntity plan) {
        return new BirthPlanResponse(plan.getId(), plan.getPregnancyId(), plan.getCompanion(),
                plan.getPreferredFacility(), plan.getPainManagementNote(), plan.getNewbornCareNote(),
                plan.getFreeTextNote(), plan.getVersion());
    }

    private long gestationalWeek(PregnancyEntity pregnancy) {
        return gestationalDays(pregnancy) / 7;
    }

    private long gestationalDays(PregnancyEntity pregnancy) {
        LocalDate today = LocalDate.now(zone(pregnancy.getTimezone()));
        long days;
        if (pregnancy.getCalculationSource() == PregnancyCalculationSource.MANUAL
                && pregnancy.getGestationalAgeAnchorDays() != null
                && pregnancy.getGestationalAgeAnchorDate() != null) {
            days = pregnancy.getGestationalAgeAnchorDays()
                    + ChronoUnit.DAYS.between(pregnancy.getGestationalAgeAnchorDate(), today);
        } else if (pregnancy.getLastMenstrualPeriod() != null) {
            days = ChronoUnit.DAYS.between(pregnancy.getLastMenstrualPeriod(), today);
        } else if (pregnancy.getEstimatedDueDate() != null) {
            days = 280L - ChronoUnit.DAYS.between(today, pregnancy.getEstimatedDueDate());
        } else {
            days = 0;
        }
        return Math.max(0, days);
    }

    private ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneOffset.UTC;
        try { return ZoneId.of(timezone); }
        catch (DateTimeException ex) { return ZoneOffset.UTC; }
    }

    private int progress(List<PreparationItemEntity> items) {
        if (items.isEmpty()) return 0;
        return (int) Math.round(items.stream().filter(PreparationItemEntity::isCompleted).count() * 100.0 / items.size());
    }

    private CarePlanProgressResponse careProgress(List<PreparationItemEntity> items) {
        return new CarePlanProgressResponse(
                (int) items.stream().filter(PreparationItemEntity::isCompleted).count(), items.size());
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) throw validation("limit must be at least 1.");
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private int decodeCursor(String cursor, int size) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            if (offset < 0 || offset > size) throw new IllegalArgumentException();
            return offset;
        } catch (IllegalArgumentException ex) {
            throw validation("cursor is invalid.");
        }
    }

    private String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "The resource was updated elsewhere. Reload and try again.");
    }

    private BusinessException validation(String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", message);
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Pregnancy context was not found.");
    }

    public record CareProgress(int total, int completed, int percentage) { }
}
