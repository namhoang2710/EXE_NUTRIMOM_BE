package vn.nutrimom.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import vn.nutrimom.family.domain.FamilyTaskPriority;

public record CreateFamilyTaskRequest(
        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must not exceed 200 characters")
        String title,

        String description,

        @NotNull(message = "priority is required")
        FamilyTaskPriority priority,

        OffsetDateTime dueAt,

        @Size(max = 36, message = "assignee_id must not exceed 36 characters")
        String assigneeId) { }
