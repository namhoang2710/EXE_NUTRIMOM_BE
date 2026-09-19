package vn.nutrimom.care.dto;

public record CarePlanMilestoneResponse(
        String id,
        int week,
        String title,
        String description,
        String status,
        String sourceName,
        String sourceUrl) { }
