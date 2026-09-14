package vn.nutrimom.user.dto;

import java.time.LocalTime;

public record UserPreferencesResponse(
        String language,
        String timezone,
        boolean notificationEnabled,
        boolean pushEnabled,
        boolean emailEnabled,
        boolean smsEnabled,
        LocalTime preferredReminderTime,
        long version) { }
