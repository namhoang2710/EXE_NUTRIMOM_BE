package vn.nutrimom.pregnancy.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record PregnancyResponse(
        String id,
        String status,
        LocalDate lastMenstrualPeriod,
        LocalDate estimatedDueDate,
        long gestationalWeek,
        int gestationalDay,
        int trimester,
        long daysUntilDue,
        String calculationSource,
        String careFacilityName,
        String careProviderName,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) { }
