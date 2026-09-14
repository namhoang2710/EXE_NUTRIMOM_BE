package vn.nutrimom.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeleteAccountRequest(
        @NotBlank(message = "Deletion reason is required")
        @Size(max = 500, message = "Deletion reason must not exceed 500 characters")
        String reason,

        @Size(max = 72, message = "Password must not exceed 72 characters")
        String password,

        @Size(max = 36, message = "OTP challenge ID must not exceed 36 characters")
        String otpChallengeId,

        @Pattern(regexp = "\\d{6}", message = "OTP code must contain exactly 6 digits")
        String otpCode) { }
