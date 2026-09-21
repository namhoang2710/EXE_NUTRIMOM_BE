package vn.nutrimom.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import vn.nutrimom.auth.domain.Gender;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;

public record AdminUserDetailResponse(
        String id,
        String displayName,
        String phone,
        @JsonInclude(JsonInclude.Include.ALWAYS) String email,
        @JsonInclude(JsonInclude.Include.ALWAYS) Gender gender,
        @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate dateOfBirth,
        @JsonInclude(JsonInclude.Include.ALWAYS) String avatarKey,
        List<UserRole> roles,
        UserStatus status,
        OnboardingStatus onboardingStatus,
        @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime termsAcceptedAt,
        @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime privacyAcceptedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version) {
}
