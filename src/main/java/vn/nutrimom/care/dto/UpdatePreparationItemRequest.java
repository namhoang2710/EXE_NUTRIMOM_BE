package vn.nutrimom.care.dto;

import jakarta.validation.constraints.NotNull;

public record UpdatePreparationItemRequest(
        Boolean completed,
        @NotNull(message = "Version is required") Long version) { }
