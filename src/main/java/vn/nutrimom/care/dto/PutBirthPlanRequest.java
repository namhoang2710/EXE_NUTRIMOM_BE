package vn.nutrimom.care.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PutBirthPlanRequest(
        @Size(max = 255) String companion,
        @Size(max = 255) String preferredFacility,
        String painManagementNote,
        String newbornCareNote,
        String freeTextNote,
        @NotNull(message = "Version is required") Long version) { }
