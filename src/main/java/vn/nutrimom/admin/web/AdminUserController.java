package vn.nutrimom.admin.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.admin.dto.AdminUserDetailResponse;
import vn.nutrimom.admin.dto.AdminUserPageResponse;
import vn.nutrimom.admin.dto.AdminUserSummaryResponse;
import vn.nutrimom.admin.service.AdminUserService;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;

@Validated
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Users", description = "Read-only user management for administrators")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {
    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List users", description = "Search, filter, sort and paginate users. Admin only.")
    public ApiResponse<AdminUserPageResponse> list(
            @Parameter(description = "One-based page number")
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @Parameter(description = "Number of users per page (1-100)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @Parameter(description = "Case-insensitive display name, phone or email search")
            @RequestParam(required = false) String q,
            @Parameter(description = "Exact user status")
            @RequestParam(required = false) UserStatus status,
            @Parameter(description = "Role membership")
            @RequestParam(required = false) UserRole role,
            @Parameter(description = "Exact onboarding status")
            @RequestParam(required = false) OnboardingStatus onboardingStatus,
            @Parameter(description = "Allowed values: createdAt, updatedAt, displayName, status")
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Allowed values: asc, desc")
            @RequestParam(defaultValue = "desc") String sortDirection) {
        return ApiResponses.success(service.list(
                page, pageSize, q, status, role, onboardingStatus, sortBy, sortDirection));
    }

    @GetMapping("/summary")
    @Operation(summary = "Get user summary", description = "Returns user totals and current-year monthly progress. Admin only.")
    public ApiResponse<AdminUserSummaryResponse> summary() {
        return ApiResponses.success(service.summary());
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get user details", description = "Returns account and basic profile fields only. Admin only.")
    public ApiResponse<AdminUserDetailResponse> detail(
            @Parameter(description = "User UUID", required = true)
            @PathVariable UUID userId) {
        return ApiResponses.success(service.detail(userId));
    }
}
