package vn.nutrimom.pregnancy.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.pregnancy.domain.PregnancyAuditEntity;
import vn.nutrimom.pregnancy.domain.PregnancyCalculationSource;
import vn.nutrimom.pregnancy.domain.PregnancyEntity;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;
import vn.nutrimom.pregnancy.dto.CalculatePregnancyRequest;
import vn.nutrimom.pregnancy.dto.CreatePregnancyRequest;
import vn.nutrimom.pregnancy.dto.PregnancyCalculationResponse;
import vn.nutrimom.pregnancy.dto.PregnancyResponse;
import vn.nutrimom.pregnancy.dto.UpdatePregnancyRequest;
import vn.nutrimom.pregnancy.repository.PregnancyAuditRepository;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;

@Service
public class PregnancyService {
    private static final Logger log = LoggerFactory.getLogger(PregnancyService.class);

    private final PregnancyRepository pregnancies;
    private final PregnancyAuditRepository pregnancyAudits;
    private final UserRepository users;
    private final AccessGuard accessGuard;
    private final PregnancyCalculationService calculator;

    public PregnancyService(PregnancyRepository pregnancies, PregnancyAuditRepository pregnancyAudits,
                            UserRepository users, AccessGuard accessGuard,
                            PregnancyCalculationService calculator) {
        this.pregnancies = pregnancies;
        this.pregnancyAudits = pregnancyAudits;
        this.users = users;
        this.accessGuard = accessGuard;
        this.calculator = calculator;
    }

    @Transactional
    public PregnancyResponse create(String userId, CreatePregnancyRequest request) {
        UserEntity owner = loadActiveUserForUpdate(userId);
        if (pregnancies.existsByOwnerUserIdAndStatus(userId, PregnancyStatus.ACTIVE)) {
            throw activePregnancyExists();
        }

        String timezone = normalizeTimezone(request.timezone());
        PregnancyCalculationService.CalculationResult calculation = calculator.calculate(
                new PregnancyCalculationService.CalculationRequest(
                        request.calculationSource(), request.lastMenstrualPeriod(), request.conceptionDate(),
                        request.estimatedDueDate(), request.gestationalWeek(), request.gestationalDay(),
                        null, timezone));

        PregnancyEntity pregnancy = new PregnancyEntity();
        pregnancy.setOwnerUserId(userId);
        pregnancy.setStatus(PregnancyStatus.ACTIVE);
        applyCalculation(pregnancy, calculation, timezone);
        pregnancy.setIsFirstPregnancy(request.isFirstPregnancy());
        pregnancy.setMultiplePregnancy(request.multiplePregnancy());
        pregnancy.setCareFacilityName(normalize(request.careFacilityName()));
        pregnancy.setCareProviderName(normalize(request.careProviderName()));
        pregnancies.saveAndFlush(pregnancy);

        if (owner.getOnboardingStatus() != OnboardingStatus.COMPLETED) {
            owner.setOnboardingStatus(OnboardingStatus.COMPLETED);
            users.saveAndFlush(owner);
        }
        return toResponse(pregnancy);
    }

    @Transactional(readOnly = true)
    public PregnancyCalculationResponse calculate(CalculatePregnancyRequest request) {
        PregnancyCalculationSource method = request.method();
        LocalDate date = request.date();
        LocalDate lmp = request.lastMenstrualPeriod();
        LocalDate conception = request.conceptionDate();
        LocalDate dueDate = request.estimatedDueDate();

        if (method == PregnancyCalculationSource.LMP
                || method == PregnancyCalculationSource.LAST_MENSTRUAL_PERIOD) {
            lmp = mergeInput(date, lmp, "last_menstrual_period");
        } else if (method == PregnancyCalculationSource.CONCEPTION_DATE) {
            conception = mergeInput(date, conception, "conception_date");
        } else if (method == PregnancyCalculationSource.EDD
                || method == PregnancyCalculationSource.ESTIMATED_DUE_DATE
                || method == PregnancyCalculationSource.ULTRASOUND
                || method == PregnancyCalculationSource.IVF) {
            dueDate = mergeInput(date, dueDate, "estimated_due_date");
        } else if (method == PregnancyCalculationSource.MANUAL && date != null) {
            throw validation("date cannot be combined with manual gestational age.");
        }

        PregnancyCalculationService.CalculationResult result = calculator.calculate(
                new PregnancyCalculationService.CalculationRequest(
                        method, lmp, conception, dueDate, request.gestationalWeek(),
                        request.gestationalDay(), null, normalizeTimezone(request.timezone())));
        return new PregnancyCalculationResponse(result.lastMenstrualPeriod(), result.conceptionDate(),
                result.estimatedDueDate(), result.gestationalWeek(), result.gestationalDay(),
                result.trimester(), result.daysUntilDue(), result.source().name());
    }

    @Transactional(readOnly = true)
    public PregnancyResponse getCurrent(String userId) {
        requireActiveUser(userId);
        PregnancyEntity pregnancy = pregnancies
                .findByOwnerUserIdAndStatus(userId, PregnancyStatus.ACTIVE)
                .orElseThrow(this::notFound);
        return toResponse(pregnancy);
    }

    @Transactional(readOnly = true)
    public PregnancyResponse getById(String userId, String pregnancyId) {
        requireActiveUser(userId);
        return toResponse(loadOwned(userId, pregnancyId));
    }

    @Transactional
    public PregnancyResponse update(String userId, String pregnancyId, UpdatePregnancyRequest request) {
        loadActiveUserForUpdate(userId);
        PregnancyEntity pregnancy = loadOwned(userId, pregnancyId);
        if (pregnancy.getStatus() == PregnancyStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.PREGNANCY_ARCHIVED, "An archived pregnancy cannot be updated.");
        }
        if (request.version() != pregnancy.getVersion()) throw versionConflict();

        PregnancyCalculationService.CalculationRequest calculationRequest = updateCalculationRequest(
                pregnancy, request);
        PregnancyCalculationService.CalculationResult calculation = calculator.calculate(calculationRequest);
        LocalDate previousDueDate = pregnancy.getEstimatedDueDate();
        String timezone = request.timezone() == null
                ? pregnancy.getTimezone() : normalizeTimezone(request.timezone());
        applyCalculation(pregnancy, calculation, timezone);

        if (!calculation.estimatedDueDate().equals(previousDueDate)) {
            pregnancyAudits.save(PregnancyAuditEntity.dueDateChanged(
                    pregnancyId, userId, previousDueDate, calculation.estimatedDueDate(), calculation.source()));
            log.info("Pregnancy due date changed: pregnancyId={}, userId={}, previous={}, updated={}, source={}",
                    pregnancyId, userId, previousDueDate, calculation.estimatedDueDate(), calculation.source());
        }
        if (request.isFirstPregnancy() != null) pregnancy.setIsFirstPregnancy(request.isFirstPregnancy());
        if (request.multiplePregnancy() != null) pregnancy.setMultiplePregnancy(request.multiplePregnancy());
        if (request.careFacilityName() != null) pregnancy.setCareFacilityName(normalize(request.careFacilityName()));
        if (request.careProviderName() != null) pregnancy.setCareProviderName(normalize(request.careProviderName()));
        if (request.status() != null && request.status() != pregnancy.getStatus()) {
            if (request.status() == PregnancyStatus.ACTIVE
                    && pregnancies.existsByOwnerUserIdAndStatusAndIdNot(
                            userId, PregnancyStatus.ACTIVE, pregnancyId)) {
                throw activePregnancyExists();
            }
            pregnancy.setStatus(request.status());
        }
        pregnancies.saveAndFlush(pregnancy);
        return toResponse(pregnancy);
    }

    private PregnancyCalculationService.CalculationRequest updateCalculationRequest(
            PregnancyEntity pregnancy, UpdatePregnancyRequest request) {
        boolean hasInput = request.estimatedDueDate() != null || request.lastMenstrualPeriod() != null
                || request.conceptionDate() != null || request.gestationalWeek() != null
                || request.gestationalDay() != null;
        PregnancyCalculationSource source = request.calculationSource();
        LocalDate lmp = request.lastMenstrualPeriod();
        LocalDate conception = request.conceptionDate();
        LocalDate dueDate = request.estimatedDueDate();
        Integer week = request.gestationalWeek();
        Integer day = request.gestationalDay();
        LocalDate anchorDate = null;

        if (!hasInput && source == null) {
            source = pregnancy.getCalculationSource();
            lmp = pregnancy.getLastMenstrualPeriod();
            conception = pregnancy.getConceptionDate();
            dueDate = pregnancy.getEstimatedDueDate();
            if (source == PregnancyCalculationSource.MANUAL
                    && pregnancy.getGestationalAgeAnchorDays() != null) {
                week = pregnancy.getGestationalAgeAnchorDays() / 7;
                day = pregnancy.getGestationalAgeAnchorDays() % 7;
                anchorDate = pregnancy.getGestationalAgeAnchorDate();
                lmp = null;
                conception = null;
                dueDate = null;
            }
        } else if (!hasInput && source != null) {
            if (source == PregnancyCalculationSource.MANUAL) {
                if (pregnancy.getGestationalAgeAnchorDays() == null) {
                    throw validation("Manual gestational age is not available for this pregnancy.");
                }
                week = pregnancy.getGestationalAgeAnchorDays() / 7;
                day = pregnancy.getGestationalAgeAnchorDays() % 7;
                anchorDate = pregnancy.getGestationalAgeAnchorDate();
                lmp = null;
                conception = null;
                dueDate = null;
            } else {
                lmp = pregnancy.getLastMenstrualPeriod();
                conception = pregnancy.getConceptionDate();
                dueDate = pregnancy.getEstimatedDueDate();
            }
        }

        return new PregnancyCalculationService.CalculationRequest(
                source, lmp, conception, dueDate, week, day, anchorDate,
                request.timezone() == null ? pregnancy.getTimezone() : normalizeTimezone(request.timezone()));
    }

    private void applyCalculation(PregnancyEntity pregnancy,
                                  PregnancyCalculationService.CalculationResult calculation,
                                  String timezone) {
        pregnancy.setEstimatedDueDate(calculation.estimatedDueDate());
        pregnancy.setLastMenstrualPeriod(calculation.lastMenstrualPeriod());
        pregnancy.setConceptionDate(calculation.conceptionDate());
        pregnancy.setCalculationSource(calculation.source());
        pregnancy.setTimezone(timezone);
        if (calculation.source() == PregnancyCalculationSource.MANUAL) {
            pregnancy.setGestationalAgeAnchorDays(calculation.anchorDays());
            pregnancy.setGestationalAgeAnchorDate(calculation.anchorDate());
        } else {
            pregnancy.setGestationalAgeAnchorDays(null);
            pregnancy.setGestationalAgeAnchorDate(null);
        }
    }

    private PregnancyResponse toResponse(PregnancyEntity pregnancy) {
        PregnancyCalculationSource source = pregnancy.getCalculationSource();
        Integer week = source == PregnancyCalculationSource.MANUAL
                && pregnancy.getGestationalAgeAnchorDays() != null
                ? pregnancy.getGestationalAgeAnchorDays() / 7 : null;
        Integer day = source == PregnancyCalculationSource.MANUAL
                && pregnancy.getGestationalAgeAnchorDays() != null
                ? pregnancy.getGestationalAgeAnchorDays() % 7 : null;
        LocalDate lmp = source == PregnancyCalculationSource.MANUAL ? null
                : pregnancy.getLastMenstrualPeriod();
        LocalDate conception = source == PregnancyCalculationSource.MANUAL ? null
                : pregnancy.getConceptionDate();
        LocalDate dueDate = source == PregnancyCalculationSource.MANUAL ? null
                : pregnancy.getEstimatedDueDate();
        PregnancyCalculationService.CalculationResult calculation = calculator.calculate(
                new PregnancyCalculationService.CalculationRequest(
                        source, lmp, conception, dueDate, week, day,
                        pregnancy.getGestationalAgeAnchorDate(), pregnancy.getTimezone()));
        return new PregnancyResponse(
                pregnancy.getId(), pregnancy.getStatus().name(), calculation.lastMenstrualPeriod(),
                calculation.conceptionDate(), calculation.estimatedDueDate(),
                pregnancy.getIsFirstPregnancy(), pregnancy.getMultiplePregnancy(), pregnancy.getTimezone(),
                calculation.gestationalWeek(), calculation.gestationalDay(), calculation.trimester(),
                calculation.daysUntilDue(), calculation.source().name(), pregnancy.getCareFacilityName(),
                pregnancy.getCareProviderName(), pregnancy.getVersion(), pregnancy.getCreatedAt(),
                pregnancy.getUpdatedAt());
    }

    private UserEntity loadActiveUserForUpdate(String userId) {
        return users.findByIdForUpdate(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "The authenticated account is unavailable."));
    }

    private void requireActiveUser(String userId) {
        users.findById(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "The authenticated account is unavailable."));
    }

    private PregnancyEntity loadOwned(String userId, String pregnancyId) {
        // Row-level: query đã kèm ownerUserId, guard chuẩn hoá việc thiếu quyền → 404 (không lộ tồn tại).
        return accessGuard.requireOwned(
                pregnancies.findByIdAndOwnerUserId(pregnancyId, userId), "Pregnancy not found.");
    }

    private LocalDate mergeInput(LocalDate primary, LocalDate fallback, String field) {
        if (primary != null && fallback != null && !primary.equals(fallback)) {
            throw validation(field + " was supplied more than once with conflicting values.");
        }
        return primary == null ? fallback : primary;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeTimezone(String value) {
        String timezone = normalize(value);
        if (timezone == null) return null;
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            throw validation("timezone must be a valid IANA timezone.");
        }
        return timezone;
    }

    private BusinessException activePregnancyExists() {
        return new BusinessException(ErrorCode.ACTIVE_PREGNANCY_EXISTS, "The user already has an active pregnancy.");
    }

    private BusinessException versionConflict() {
        return new BusinessException(ErrorCode.VERSION_CONFLICT, "The pregnancy was updated elsewhere. Reload and try again.");
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Pregnancy not found.");
    }
}
