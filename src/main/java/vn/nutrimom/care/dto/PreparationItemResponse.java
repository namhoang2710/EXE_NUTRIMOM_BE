package vn.nutrimom.care.dto;

import java.time.OffsetDateTime;

public record PreparationItemResponse(
        String id,
        String groupCode,
        String title,
        boolean completed,
        OffsetDateTime completedAt,
        int sortOrder,
        long version) { }
