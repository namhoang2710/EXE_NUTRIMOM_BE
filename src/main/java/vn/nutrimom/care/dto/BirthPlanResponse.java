package vn.nutrimom.care.dto;

public record BirthPlanResponse(
        String id,
        String pregnancyId,
        String companion,
        String preferredFacility,
        String painManagementNote,
        String newbornCareNote,
        String freeTextNote,
        long version) { }
