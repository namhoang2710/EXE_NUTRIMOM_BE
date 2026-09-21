package vn.nutrimom.pregnancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import vn.nutrimom.pregnancy.domain.PregnancyCalculationSource;

public record CalculatePregnancyRequest(
        @NotNull(message = "method is required") PregnancyCalculationSource method,
        LocalDate date,
        LocalDate lastMenstrualPeriod,
        LocalDate conceptionDate,
        LocalDate estimatedDueDate,
        @Min(value = 0, message = "gestational_week must be between 0 and 42")
        @Max(value = 42, message = "gestational_week must be between 0 and 42") Integer gestationalWeek,
        @Min(value = 0, message = "gestational_day must be between 0 and 6")
        @Max(value = 6, message = "gestational_day must be between 0 and 6") Integer gestationalDay,
        @Size(max = 50, message = "Timezone must not exceed 50 characters") String timezone) { }
