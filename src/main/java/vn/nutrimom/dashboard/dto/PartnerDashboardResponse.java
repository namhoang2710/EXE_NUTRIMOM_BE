package vn.nutrimom.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerDashboardResponse(
        String membershipRole,
        PartnerPregnancyOverviewResponse pregnancyOverview,
        List<FamilyTaskResponse> assignedTasks,
        List<Object> sharedCalendar,
        List<Object> allowedAlerts,
        List<Object> activityFeed) { }
