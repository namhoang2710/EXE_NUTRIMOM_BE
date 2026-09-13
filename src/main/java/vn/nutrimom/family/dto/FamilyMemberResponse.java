package vn.nutrimom.family.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record FamilyMemberResponse(
        String id,
        String familyGroupId,
        String userId,
        String relationship,
        String membershipRole,
        List<String> scopes,
        String status,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) { }
