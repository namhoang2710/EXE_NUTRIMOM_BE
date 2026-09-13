package vn.nutrimom.pregnancy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import vn.nutrimom.pregnancy.domain.PregnancyCalculationSource;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;

public record UpdatePregnancyRequest(
        LocalDate estimatedDueDate,
        LocalDate lastMenstrualPeriod,
        PregnancyCalculationSource calculationSource,

        @Size(max = 255, message = "Care facility name must not exceed 255 characters")
        String careFacilityName,

        @Size(max = 255, message = "Care provider name must not exceed 255 characters")
        String careProviderName,
        PregnancyStatus status,

        @NotNull(message = "Version is required")
        Long version) { }
