package vn.nutrimom.family.dto;

import java.time.OffsetDateTime;

public record FamilyGroupResponse(
        String id,
        String pregnancyId,
        String ownerUserId,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) { }
