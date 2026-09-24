package vn.nutrimom.pregnancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import vn.nutrimom.pregnancy.domain.PregnancyCalculationSource;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;

public record UpdatePregnancyRequest(
        LocalDate estimatedDueDate,
        LocalDate lastMenstrualPeriod,
        LocalDate conceptionDate,

        @Min(value = 0, message = "gestational_week must be between 0 and 42")
        @Max(value = 42, message = "gestational_week must be between 0 and 42")
        Integer gestationalWeek,

        @Min(value = 0, message = "gestational_day must be between 0 and 6")
        @Max(value = 6, message = "gestational_day must be between 0 and 6")
        Integer gestationalDay,

        Boolean isFirstPregnancy,
        Boolean multiplePregnancy,

        @Size(max = 50, message = "Timezone must not exceed 50 characters")
        String timezone,

        PregnancyCalculationSource calculationSource,

        @Size(max = 255, message = "Care facility name must not exceed 255 characters")
        String careFacilityName,

        @Size(max = 255, message = "Care provider name must not exceed 255 characters")
        String careProviderName,
        PregnancyStatus status,

        @NotNull(message = "Version is required")
        Long version) { }
