package vn.nutrimom.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record MomDashboardResponse(
        ProfileSummaryResponse profileSummary,
        PregnancySummaryResponse pregnancySummary,
        BabySummaryResponse babySummary,
        Object nextAppointment,
        Object healthSnapshot,
        Object careProgress,
        List<Object> activeAlerts,
        List<Object> recommendedArticles,
        List<Object> upcomingReminders,
        Object scanQuota,
        Object subscription,
        long unreadNotificationCount) { }
