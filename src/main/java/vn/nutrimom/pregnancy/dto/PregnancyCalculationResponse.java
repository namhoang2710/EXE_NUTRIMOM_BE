package vn.nutrimom.pregnancy.dto;

import java.time.LocalDate;

public record PregnancyCalculationResponse(
        LocalDate lastMenstrualPeriod,
        LocalDate conceptionDate,
        LocalDate estimatedDueDate,
        long gestationalWeek,
        int gestationalDay,
        int trimester,
        long daysUntilDue,
        String calculationSource) { }
