package vn.nutrimom.pregnancy.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import vn.nutrimom.pregnancy.domain.PregnancyCalculationSource;
import vn.nutrimom.pregnancy.domain.PregnancyEntity;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;
import vn.nutrimom.pregnancy.dto.CreatePregnancyRequest;
import vn.nutrimom.pregnancy.dto.PregnancyResponse;
import vn.nutrimom.pregnancy.dto.UpdatePregnancyRequest;
import vn.nutrimom.pregnancy.domain.PregnancyAuditEntity;
import vn.nutrimom.pregnancy.repository.PregnancyAuditRepository;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;

@Service
public class PregnancyService {
    private static final Logger log = LoggerFactory.getLogger(PregnancyService.class);
    private static final long PREGNANCY_DAYS = 280;

    private final PregnancyRepository pregnancies;
    private final PregnancyAuditRepository pregnancyAudits;
    private final UserRepository users;

    public PregnancyService(PregnancyRepository pregnancies, PregnancyAuditRepository pregnancyAudits,
                            UserRepository users) {
        this.pregnancies = pregnancies;
        this.pregnancyAudits = pregnancyAudits;
        this.users = users;
    }

    @Transactional
    public PregnancyResponse create(String userId, CreatePregnancyRequest request) {
        UserEntity owner = loadActiveUserForUpdate(userId);
        if (pregnancies.existsByOwnerUserIdAndStatus(userId, PregnancyStatus.ACTIVE)) {
            throw activePregnancyExists();
        }

        LocalDate dueDate = request.estimatedDueDate();
        LocalDate lmp = request.lastMenstrualPeriod();
        if (dueDate == null && lmp == null) {
            throw validation("Either estimated_due_date or last_menstrual_period is required.");
        }
        if (lmp != null && lmp.isAfter(today())) {
            throw validation("last_menstrual_period must not be in the future.");
        }
        if (lmp == null) {
            lmp = dueDate.minusDays(PREGNANCY_DAYS);
        }
        if (dueDate == null) {
            dueDate = lmp.plusDays(PREGNANCY_DAYS);
        }
        validateDatePair(lmp, dueDate);

        PregnancyEntity pregnancy = new PregnancyEntity();
        pregnancy.setOwnerUserId(userId);
        pregnancy.setStatus(PregnancyStatus.ACTIVE);
        pregnancy.setEstimatedDueDate(dueDate);
        pregnancy.setLastMenstrualPeriod(lmp);
        pregnancy.setCalculationSource(resolveSource(request));
        pregnancy.setCareFacilityName(normalize(request.careFacilityName()));
        pregnancy.setCareProviderName(normalize(request.careProviderName()));
        pregnancies.saveAndFlush(pregnancy);

        if (owner.getOnboardingStatus() == OnboardingStatus.CONTEXT_REQUIRED) {
            owner.setOnboardingStatus(OnboardingStatus.COMPLETED);
            users.saveAndFlush(owner);
        }
        return toResponse(pregnancy);
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
        if (request.version() != pregnancy.getVersion()) {
            throw versionConflict();
        }
        LocalDate previousDueDate = pregnancy.getEstimatedDueDate();
        LocalDate updatedLmp = request.lastMenstrualPeriod();
        LocalDate updatedDueDate = request.estimatedDueDate();
        if (updatedLmp == null && updatedDueDate == null) {
            updatedLmp = pregnancy.getLastMenstrualPeriod();
            updatedDueDate = pregnancy.getEstimatedDueDate();
        } else if (updatedLmp == null) {
            updatedLmp = updatedDueDate.minusDays(PREGNANCY_DAYS);
        } else if (updatedDueDate == null) {
            updatedDueDate = updatedLmp.plusDays(PREGNANCY_DAYS);
        }
        validateDatePair(updatedLmp, updatedDueDate);
        pregnancy.setLastMenstrualPeriod(updatedLmp);
        pregnancy.setEstimatedDueDate(updatedDueDate);
        if (!updatedDueDate.equals(previousDueDate)) {
            pregnancyAudits.save(PregnancyAuditEntity.dueDateChanged(
                    pregnancyId, userId, previousDueDate, updatedDueDate));
            log.info("Pregnancy due date changed: pregnancyId={}, userId={}, previous={}, updated={}",
                    pregnancyId, userId, previousDueDate, updatedDueDate);
        }
        if (request.calculationSource() != null) {
            pregnancy.setCalculationSource(request.calculationSource());
        }
        if (request.careFacilityName() != null) {
            pregnancy.setCareFacilityName(normalize(request.careFacilityName()));
        }
        if (request.careProviderName() != null) {
            pregnancy.setCareProviderName(normalize(request.careProviderName()));
        }
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
        return pregnancies.findByIdAndOwnerUserId(pregnancyId, userId)
                .orElseThrow(this::notFound);
    }

    private PregnancyCalculationSource resolveSource(CreatePregnancyRequest request) {
        if (request.calculationSource() != null) {
            return request.calculationSource();
        }
        return request.lastMenstrualPeriod() != null
                ? PregnancyCalculationSource.LMP : PregnancyCalculationSource.EDD;
    }

    private PregnancyResponse toResponse(PregnancyEntity pregnancy) {
        LocalDate today = today();
        long gestationalDays = Math.max(0,
                ChronoUnit.DAYS.between(pregnancy.getLastMenstrualPeriod(), today));
        long week = gestationalDays / 7;
        int day = (int) (gestationalDays % 7);
        int trimester = week <= 13 ? 1 : week <= 27 ? 2 : 3;
        return new PregnancyResponse(
                pregnancy.getId(), pregnancy.getStatus().name(),
                pregnancy.getLastMenstrualPeriod(), pregnancy.getEstimatedDueDate(),
                week, day, trimester,
                ChronoUnit.DAYS.between(today, pregnancy.getEstimatedDueDate()),
                pregnancy.getCalculationSource().name(),
                pregnancy.getCareFacilityName(), pregnancy.getCareProviderName(),
                pregnancy.getVersion(), pregnancy.getCreatedAt(), pregnancy.getUpdatedAt());
    }

    private LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private void validateDatePair(LocalDate lmp, LocalDate dueDate) {
        if (lmp.isAfter(today())) {
            throw validation("last_menstrual_period must not be in the future.");
        }
        if (!dueDate.equals(lmp.plusDays(PREGNANCY_DAYS))) {
            throw validation("estimated_due_date must be 280 days after last_menstrual_period.");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
