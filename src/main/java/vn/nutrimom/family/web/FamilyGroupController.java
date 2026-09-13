package vn.nutrimom.family.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.family.dto.CreateFamilyGroupRequest;
import vn.nutrimom.family.dto.FamilyGroupResponse;
import vn.nutrimom.family.service.FamilyGroupService;

@RestController
@RequestMapping("/api/v1/family-groups")
@Tag(name = "Family sharing", description = "Pregnancy family groups and scoped sharing")
@SecurityRequirement(name = "bearerAuth")
public class FamilyGroupController {
    private final FamilyGroupService service;

    public FamilyGroupController(FamilyGroupService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List family groups accessible to the authenticated account")
    public ApiResponse<List<FamilyGroupResponse>> getGroups(
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.getAccessibleGroups(jwt.getSubject()));
    }

    @PostMapping
    @Operation(summary = "Create the pregnancy owner's active family group")
    public ResponseEntity<ApiResponse<FamilyGroupResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody(required = false) CreateFamilyGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(service.create(jwt.getSubject(), request)));
    }
}
