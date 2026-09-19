package vn.nutrimom.pregnancy.dto;

import java.time.OffsetDateTime;
import java.util.List;

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
        OffsetDateTime updatedAt,
        PregnancyBabyContentResponse baby,
        String developmentSummary,
        List<String> maternalChanges,
        List<String> careTopics,
        String reviewedBy,
        OffsetDateTime reviewedAt,
        OffsetDateTime nextReviewAt,
        int contentVersion) { }
