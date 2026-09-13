package vn.nutrimom.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

public record UpdatePreferencesRequest(
        @Pattern(regexp = "^[A-Za-z]{2,3}([_-][A-Za-z]{2})?$", message = "Language code is invalid")
        String language,

        @Size(max = 50, message = "Timezone must not exceed 50 characters")
        String timezone,

        Boolean notificationEnabled,
        Boolean pushEnabled,
        Boolean emailEnabled,
        Boolean smsEnabled,
        LocalTime preferredReminderTime,

        @NotNull(message = "Version is required")
        Long version) { }
