package vn.nutrimom.reference.dto;

import java.util.List;

public record ReferenceRoleResponse(
        String code,
        String displayName,
        String description,
        List<String> allowedRelationships,
        int sortOrder) { }
