package vn.nutrimom.pregnancy.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.pregnancy.domain.PregnancyCalculationSource;

@Service
public class PregnancyCalculationService {
    public static final int OBSTETRIC_DAYS = 280;
    public static final int CONCEPTION_TO_DUE_DAYS = 266;
    private static final String DEFAULT_TIMEZONE = "Asia/Ho_Chi_Minh";

    private final Clock clock;

    public PregnancyCalculationService(Clock clock) {
        this.clock = clock;
    }

    public CalculationResult calculate(CalculationRequest request) {
        PregnancyCalculationSource source = resolveSource(request);
        ZoneId zone = zone(request.timezone());
        LocalDate today = LocalDate.now(clock.withZone(zone));

        return switch (source) {
            case LMP, LAST_MENSTRUAL_PERIOD -> fromLmp(request, today, source);
            case CONCEPTION_DATE -> fromConception(request, today, source);
            case EDD, ESTIMATED_DUE_DATE, ULTRASOUND, IVF -> fromDueDate(request, today, source);
            case MANUAL -> fromManual(request, today, source);
        };
    }

    private CalculationResult fromLmp(CalculationRequest request, LocalDate today,
                                      PregnancyCalculationSource source) {
        LocalDate lmp = required(request.lastMenstrualPeriod(), "last_menstrual_period");
        rejectFuture(lmp, today, "last_menstrual_period");
        LocalDate dueDate = lmp.plusDays(OBSTETRIC_DAYS);
        validateConsistent(request.conceptionDate(), lmp.plusDays(14), "conception_date");
        validateConsistent(request.estimatedDueDate(), dueDate, "estimated_due_date");
        return result(source, lmp, request.conceptionDate(), dueDate, ageFromLmp(lmp, today), today);
    }

    private CalculationResult fromConception(CalculationRequest request, LocalDate today,
                                             PregnancyCalculationSource source) {
        LocalDate conception = required(request.conceptionDate(), "conception_date");
        rejectFuture(conception, today, "conception_date");
        LocalDate dueDate = conception.plusDays(CONCEPTION_TO_DUE_DAYS);
        validateConsistent(request.lastMenstrualPeriod(), conception.minusDays(14),
                "last_menstrual_period");
        validateConsistent(request.estimatedDueDate(), dueDate, "estimated_due_date");
        LocalDate lmp = conception.minusDays(14);
        return result(source, lmp, conception, dueDate,
                Math.max(0, ChronoUnit.DAYS.between(conception, today) + 14), today);
    }

    private CalculationResult fromDueDate(CalculationRequest request, LocalDate today,
                                          PregnancyCalculationSource source) {
        LocalDate dueDate = required(request.estimatedDueDate(), "estimated_due_date");
        LocalDate lmp = dueDate.minusDays(OBSTETRIC_DAYS);
        validateConsistent(request.lastMenstrualPeriod(), lmp, "last_menstrual_period");
        validateConsistent(request.conceptionDate(), dueDate.minusDays(CONCEPTION_TO_DUE_DAYS),
                "conception_date");
        return result(source, lmp, request.conceptionDate(), dueDate, ageFromLmp(lmp, today), today);
    }

    private CalculationResult fromManual(CalculationRequest request, LocalDate today,
                                         PregnancyCalculationSource source) {
        if (request.lastMenstrualPeriod() != null || request.conceptionDate() != null
                || request.estimatedDueDate() != null) {
            throw validation("Manual gestational age cannot be combined with pregnancy dates.");
        }
        if (request.gestationalWeek() == null || request.gestationalDay() == null) {
            throw validation("gestational_week and gestational_day are required for manual calculation.");
        }
        LocalDate anchorDate = request.anchorDate() == null ? today : request.anchorDate();
        int anchorDays;
        try {
            anchorDays = Math.addExact(Math.multiplyExact(request.gestationalWeek(), 7),
                    request.gestationalDay());
        } catch (ArithmeticException ex) {
            throw validation("gestational_week is too large for the supported storage format.");
        }
        LocalDate dueDate = anchorDate.plusDays(OBSTETRIC_DAYS - anchorDays);
        long ageDays = Math.max(0, anchorDays + ChronoUnit.DAYS.between(anchorDate, today));
        return result(source, null, null, dueDate, ageDays, today,
                anchorDays, anchorDate);
    }

    private CalculationResult result(PregnancyCalculationSource source, LocalDate lmp,
                                     LocalDate conception, LocalDate dueDate, long ageDays,
                                     LocalDate today) {
        return result(source, lmp, conception, dueDate, ageDays, today, null, null);
    }

    private CalculationResult result(PregnancyCalculationSource source, LocalDate lmp,
                                     LocalDate conception, LocalDate dueDate, long ageDays,
                                     LocalDate today, Integer anchorDays, LocalDate anchorDate) {
        long gestationalWeek = ageDays / 7;
        int gestationalDay = (int) (ageDays % 7);
        int trimester = gestationalWeek <= 13 ? 1 : gestationalWeek <= 27 ? 2 : 3;
        return new CalculationResult(source, lmp, conception, dueDate, gestationalWeek,
                gestationalDay, trimester, ChronoUnit.DAYS.between(today, dueDate),
                anchorDays, anchorDate);
    }

    private PregnancyCalculationSource resolveSource(CalculationRequest request) {
        if (request.source() != null) return request.source();
        if (request.gestationalWeek() != null || request.gestationalDay() != null) return PregnancyCalculationSource.MANUAL;
        if (request.conceptionDate() != null) return PregnancyCalculationSource.CONCEPTION_DATE;
        if (request.lastMenstrualPeriod() != null) return PregnancyCalculationSource.LMP;
        if (request.estimatedDueDate() != null) return PregnancyCalculationSource.EDD;
        throw validation("One pregnancy calculation input is required.");
    }

    private long ageFromLmp(LocalDate lmp, LocalDate today) {
        return Math.max(0, ChronoUnit.DAYS.between(lmp, today));
    }

    private LocalDate required(LocalDate value, String field) {
        if (value == null) throw validation(field + " is required for this calculation source.");
        return value;
    }

    private void rejectFuture(LocalDate value, LocalDate today, String field) {
        if (value.isAfter(today)) throw validation(field + " must not be in the future.");
    }

    private void validateConsistent(LocalDate supplied, LocalDate derived, String field) {
        if (supplied != null && !supplied.equals(derived)) {
            throw validation(field + " is inconsistent with the selected calculation source.");
        }
    }

    private ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneId.of(DEFAULT_TIMEZONE);
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException ex) {
            throw validation("timezone must be a valid IANA timezone.");
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", message);
    }

    public record CalculationRequest(PregnancyCalculationSource source,
                                     LocalDate lastMenstrualPeriod,
                                     LocalDate conceptionDate,
                                     LocalDate estimatedDueDate,
                                     Integer gestationalWeek,
                                     Integer gestationalDay,
                                     LocalDate anchorDate,
                                     String timezone) { }

    public record CalculationResult(PregnancyCalculationSource source,
                                    LocalDate lastMenstrualPeriod,
                                    LocalDate conceptionDate,
                                    LocalDate estimatedDueDate,
                                    long gestationalWeek,
                                    int gestationalDay,
                                    int trimester,
                                    long daysUntilDue,
                                    Integer anchorDays,
                                    LocalDate anchorDate) { }
}
