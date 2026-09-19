package vn.nutrimom.user.dto;

import java.time.LocalTime;
import java.util.Map;

public record UserPreferencesResponse(
        String language,
        String locale,
        String timezone,
        String theme,
        String weightUnit,
        String lengthUnit,
        String glucoseUnit,
        boolean backupEnabled,
        boolean notificationEnabled,
        boolean pushEnabled,
        boolean emailEnabled,
        boolean smsEnabled,
        LocalTime preferredReminderTime,
        Map<String, String> quietHours,
        long version) { }
