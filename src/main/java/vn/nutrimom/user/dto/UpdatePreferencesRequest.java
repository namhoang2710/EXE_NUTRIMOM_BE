package vn.nutrimom.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.Map;

public record UpdatePreferencesRequest(
        @Pattern(regexp = "^[A-Za-z]{2,3}([_-][A-Za-z]{2})?$", message = "Language code is invalid")
        String language,

        @Size(max = 20, message = "Locale must not exceed 20 characters")
        String locale,

        @Size(max = 50, message = "Timezone must not exceed 50 characters")
        String timezone,

        @Size(max = 20, message = "Theme must not exceed 20 characters")
        String theme,

        @Size(max = 20, message = "Weight unit must not exceed 20 characters")
        String weightUnit,

        @Size(max = 20, message = "Length unit must not exceed 20 characters")
        String lengthUnit,

        @Size(max = 20, message = "Glucose unit must not exceed 20 characters")
        String glucoseUnit,

        Boolean backupEnabled,

        Boolean notificationEnabled,
        Boolean pushEnabled,
        Boolean emailEnabled,
        Boolean smsEnabled,
        LocalTime preferredReminderTime,

        Map<String, String> quietHours,

        @NotNull(message = "Version is required")
        Long version) { }
