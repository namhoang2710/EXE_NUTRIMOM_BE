package vn.nutrimom.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;

public record AdminUserListItemResponse(
        String id,
        String displayName,
        String phone,
        @JsonInclude(JsonInclude.Include.ALWAYS) String email,
        @JsonInclude(JsonInclude.Include.ALWAYS) String avatarKey,
        List<UserRole> roles,
        UserStatus status,
        OnboardingStatus onboardingStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
