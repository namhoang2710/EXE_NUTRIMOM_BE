package vn.nutrimom.user.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.user.dto.UpdatePreferencesRequest;
import vn.nutrimom.user.dto.UserPreferencesResponse;
import vn.nutrimom.user.service.UserPreferenceService;

@RestController
@RequestMapping("/api/v1/users/me/preferences")
@Tag(name = "User preferences", description = "Preferences for the authenticated user")
@SecurityRequirement(name = "bearerAuth")
public class UserPreferencesController {
    private final UserPreferenceService service;

    public UserPreferencesController(UserPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get preferences for the authenticated user")
    public ApiResponse<UserPreferencesResponse> get(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.getPreferences(jwt.getSubject()));
    }

    @PatchMapping
    @Operation(summary = "Partially update preferences using optimistic locking")
    public ApiResponse<UserPreferencesResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdatePreferencesRequest request) {
        return ApiResponses.success(service.updatePreferences(jwt.getSubject(), request));
    }
}
