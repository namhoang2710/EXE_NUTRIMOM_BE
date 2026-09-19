package vn.nutrimom.care.dto;

import java.util.List;

public record CarePlanResponse(
        String pregnancyId,
        List<CarePlanMilestoneResponse> milestones,
        long week,
        CarePlanProgressResponse progress) { }
