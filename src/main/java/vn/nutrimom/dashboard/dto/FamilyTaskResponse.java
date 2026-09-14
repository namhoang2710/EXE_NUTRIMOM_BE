package vn.nutrimom.dashboard.dto;

import java.time.OffsetDateTime;

public record FamilyTaskResponse(
        String id,
        String title,
        String description,
        String priority,
        OffsetDateTime dueAt,
        String status,
        long version) { }
