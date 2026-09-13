package vn.nutrimom.dashboard.dto;

import java.time.LocalDate;

public record PregnancySummaryResponse(
        String id,
        String status,
        long gestationalWeek,
        int gestationalDay,
        int trimester,
        LocalDate estimatedDueDate,
        long daysUntilDue,
        String careFacilityName) { }
