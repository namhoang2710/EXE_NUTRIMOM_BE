package vn.nutrimom.family.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.family.dto.AcceptFamilyInvitationRequest;
import vn.nutrimom.family.dto.CreateFamilyInvitationRequest;
import vn.nutrimom.family.dto.FamilyInvitationResponse;
import vn.nutrimom.family.dto.FamilyMemberResponse;
import vn.nutrimom.family.service.FamilyInvitationService;

@RestController
@RequestMapping("/api/v1/family-invitations")
@Tag(name = "Family sharing", description = "Pregnancy family groups and scoped sharing")
@SecurityRequirement(name = "bearerAuth")
public class FamilyInvitationController {
    private final FamilyInvitationService service;

    public FamilyInvitationController(FamilyInvitationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create an expiring one-time invitation as pregnancy owner")
    public ResponseEntity<ApiResponse<FamilyInvitationResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateFamilyInvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(service.create(jwt.getSubject(), request)));
    }

    @PostMapping("/accept")
    @Operation(summary = "Accept an invitation as the authenticated invited account")
    public ApiResponse<FamilyMemberResponse> accept(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AcceptFamilyInvitationRequest request) {
        return ApiResponses.success(service.accept(jwt.getSubject(), request));
    }
}
