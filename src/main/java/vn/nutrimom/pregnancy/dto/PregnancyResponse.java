package vn.nutrimom.pregnancy.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record PregnancyResponse(
        String id,
        String status,
        LocalDate lastMenstrualPeriod,
        LocalDate conceptionDate,
        LocalDate estimatedDueDate,
        Boolean isFirstPregnancy,
        Boolean multiplePregnancy,
        String timezone,
        Long gestationalWeek,
        Integer gestationalDay,
        Integer trimester,
        Long daysUntilDue,
        String calculationSource,
        String careFacilityName,
        String careProviderName,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) { }
