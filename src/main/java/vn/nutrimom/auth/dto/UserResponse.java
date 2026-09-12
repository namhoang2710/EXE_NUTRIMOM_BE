package vn.nutrimom.auth.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record UserResponse(String id, String phone, String displayName,
                           List<String> roles, String status, String onboardingStatus,
                           OffsetDateTime createdAt) { }
