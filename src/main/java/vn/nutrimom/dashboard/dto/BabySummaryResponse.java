package vn.nutrimom.dashboard.dto;

public record BabySummaryResponse(
        int week,
        String title,
        String summary,
        String babyDevelopment,
        String disclaimer) { }
