package vn.nutrimom.pregnancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import vn.nutrimom.pregnancy.domain.PregnancyCalculationSource;

public record CreatePregnancyRequest(
        LocalDate estimatedDueDate,
        LocalDate lastMenstrualPeriod,
        LocalDate conceptionDate,

        @Min(value = 0, message = "gestational_week must be non-negative")
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
        String careProviderName) { }
