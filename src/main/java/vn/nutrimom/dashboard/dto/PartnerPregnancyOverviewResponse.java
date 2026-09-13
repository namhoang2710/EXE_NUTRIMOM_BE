package vn.nutrimom.dashboard.dto;

import java.time.LocalDate;

public record PartnerPregnancyOverviewResponse(
        String id,
        String status,
        long gestationalWeek,
        int gestationalDay,
        int trimester,
        LocalDate estimatedDueDate,
        long daysUntilDue,
        String careFacilityName) { }
