package vn.nutrimom.care.dto;

import java.time.OffsetDateTime;

public record VerifiedGuidanceResponse(
        String id,
        Integer week,
        String topic,
        String locale,
        String title,
        String summary,
        String source,
        String sourceUrl,
        String reviewer,
        OffsetDateTime reviewedAt,
        OffsetDateTime nextReviewAt,
        String evidenceLevel,
        String disclaimer) { }
