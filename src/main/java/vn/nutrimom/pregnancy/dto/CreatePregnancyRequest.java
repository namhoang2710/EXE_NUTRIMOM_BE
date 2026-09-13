package vn.nutrimom.pregnancy.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import vn.nutrimom.pregnancy.domain.PregnancyCalculationSource;

public record CreatePregnancyRequest(
        LocalDate estimatedDueDate,
        LocalDate lastMenstrualPeriod,
        PregnancyCalculationSource calculationSource,

        @Size(max = 255, message = "Care facility name must not exceed 255 characters")
        String careFacilityName,

        @Size(max = 255, message = "Care provider name must not exceed 255 characters")
        String careProviderName) { }
