package vn.nutrimom.dashboard.dto;

public record ProfileSummaryResponse(
        String id,
        String displayName,
        String salutation,
        String role) { }
