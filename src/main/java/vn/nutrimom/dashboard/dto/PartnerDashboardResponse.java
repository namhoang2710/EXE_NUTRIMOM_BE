package vn.nutrimom.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import vn.nutrimom.calendar.dto.CalendarDtos.CalendarEventItem;
import vn.nutrimom.notification.dto.NotificationDtos.ActivityEventResponse;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerDashboardResponse(
        String membershipRole,
        PartnerPregnancyOverviewResponse pregnancyOverview,
        List<FamilyTaskResponse> assignedTasks,
        List<CalendarEventItem> sharedCalendar,
        List<Object> allowedAlerts,
        List<ActivityEventResponse> activityFeed) { }
