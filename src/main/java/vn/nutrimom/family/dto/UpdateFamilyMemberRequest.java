package vn.nutrimom.family.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import vn.nutrimom.family.domain.FamilyScope;

public record UpdateFamilyMemberRequest(
        @NotEmpty(message = "At least one scope is required")
        Set<FamilyScope> scopes,

        @NotNull(message = "version is required")
        Long version) { }
