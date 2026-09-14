package vn.nutrimom.family.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.family.dto.FamilyMemberResponse;
import vn.nutrimom.family.dto.UpdateFamilyMemberRequest;
import vn.nutrimom.family.service.FamilyMemberService;

@RestController
@RequestMapping("/api/v1/family-members")
@Tag(name = "Family sharing", description = "Pregnancy family groups and scoped sharing")
@SecurityRequirement(name = "bearerAuth")
public class FamilyMemberController {
    private final FamilyMemberService service;

    public FamilyMemberController(FamilyMemberService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List active family memberships visible to the caller")
    public ApiResponse<List<FamilyMemberResponse>> getMembers(
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.getAccessibleMembers(jwt.getSubject()));
    }

    @PatchMapping("/{memberId}")
    @Operation(summary = "Replace member scopes as the pregnancy owner")
    public ApiResponse<FamilyMemberResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String memberId,
            @Valid @RequestBody UpdateFamilyMemberRequest request) {
        return ApiResponses.success(
                service.update(jwt.getSubject(), memberId, request));
    }

    @DeleteMapping("/{memberId}")
    @Operation(summary = "Revoke a family membership immediately")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String memberId) {
        service.revoke(jwt.getSubject(), memberId);
        return ResponseEntity.noContent().build();
    }
}
