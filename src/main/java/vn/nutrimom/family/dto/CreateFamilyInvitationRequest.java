package vn.nutrimom.family.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import vn.nutrimom.family.domain.FamilyRelationship;
import vn.nutrimom.family.domain.FamilyScope;

public record CreateFamilyInvitationRequest(
        @Size(max = 20, message = "invited_phone must not exceed 20 characters")
        String invitedPhone,

        @Email(message = "invited_email format is invalid")
        @Size(max = 255, message = "invited_email must not exceed 255 characters")
        String invitedEmail,

        @NotNull(message = "relationship is required")
        FamilyRelationship relationship,

        @NotEmpty(message = "At least one scope is required")
        Set<FamilyScope> scopes,

        @Min(value = 1, message = "expires_in_hours must be at least 1")
        @Max(value = 168, message = "expires_in_hours must not exceed 168")
        Integer expiresInHours) { }
