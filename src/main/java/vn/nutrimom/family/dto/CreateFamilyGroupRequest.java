package vn.nutrimom.family.dto;

import jakarta.validation.constraints.Size;

public record CreateFamilyGroupRequest(
        @Size(max = 36, message = "pregnancy_id must not exceed 36 characters")
        String pregnancyId) { }
