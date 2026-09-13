package vn.nutrimom.family.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record FamilyInvitationResponse(
        String id,
        String familyGroupId,
        String invitedPhone,
        String invitedEmail,
        String token,
        String relationship,
        List<String> scopes,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) { }
