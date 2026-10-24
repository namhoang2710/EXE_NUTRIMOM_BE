package vn.nutrimom.dashboard.dto;

import java.time.LocalDate;

public record PregnancySummaryResponse(
        String id,
        String status,
        Long gestationalWeek,
        Integer gestationalDay,
        Integer trimester,
        LocalDate estimatedDueDate,
        Long daysUntilDue,
        String careFacilityName) { }
