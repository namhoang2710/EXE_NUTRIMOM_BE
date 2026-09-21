package vn.nutrimom.family.dto;

import java.time.OffsetDateTime;

public record FamilyTaskResponse(
        String id,
        String familyGroupId,
        String title,
        String description,
        String priority,
        OffsetDateTime dueAt,
        String assigneeId,
        String status,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) { }
