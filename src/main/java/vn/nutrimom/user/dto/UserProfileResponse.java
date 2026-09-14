package vn.nutrimom.user.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Hồ sơ đầy đủ của người dùng hiện tại (GET/PATCH /users/me).
 * {@code role} là persona chính suy ra từ tập system role của user.
 * {@code avatarUrl} hiện luôn null cho tới khi có object storage cho avatar.
 */
public record UserProfileResponse(
        String id,
        String phone,
        String email,
        String displayName,
        String salutation,
        String role,
        String gender,
        LocalDate dateOfBirth,
        String avatarKey,
        String avatarUrl,
        String onboardingStatus,
        OffsetDateTime createdAt,
        long version) { }
