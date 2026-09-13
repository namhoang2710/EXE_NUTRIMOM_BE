package vn.nutrimom.pregnancy.dto;

import java.time.OffsetDateTime;

public record PregnancyWeekContentResponse(
        int week,
        String title,
        String summary,
        String babyDevelopment,
        String motherChanges,
        String careTips,
        String warningSigns,
        String sources,
        String disclaimer,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) { }
