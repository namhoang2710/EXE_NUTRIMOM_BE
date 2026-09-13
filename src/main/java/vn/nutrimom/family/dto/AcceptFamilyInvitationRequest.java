package vn.nutrimom.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptFamilyInvitationRequest(
        @NotBlank(message = "Invitation token is required")
        @Size(max = 200, message = "Invitation token is too long")
        String token) { }
