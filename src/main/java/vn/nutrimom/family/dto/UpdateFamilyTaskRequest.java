package vn.nutrimom.family.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import vn.nutrimom.family.domain.FamilyTaskPriority;
import vn.nutrimom.family.domain.FamilyTaskStatus;

/**
 * PATCH complete/reassign. Các field null → giữ nguyên; {@code version} bắt buộc cho optimistic lock.
 */
public record UpdateFamilyTaskRequest(
        @Size(max = 200, message = "title must not exceed 200 characters")
        String title,

        String description,

        FamilyTaskPriority priority,

        OffsetDateTime dueAt,

        @Size(max = 36, message = "assignee_id must not exceed 36 characters")
        String assigneeId,

        FamilyTaskStatus status,

        @NotNull(message = "version is required")
        Long version) { }
