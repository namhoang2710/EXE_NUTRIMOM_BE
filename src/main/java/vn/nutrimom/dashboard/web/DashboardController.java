package vn.nutrimom.dashboard.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.dashboard.dto.MomDashboardResponse;
import vn.nutrimom.dashboard.dto.PartnerDashboardResponse;
import vn.nutrimom.dashboard.service.MomDashboardService;
import vn.nutrimom.dashboard.service.PartnerDashboardService;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Role-aware aggregate dashboards")
@SecurityRequirement(name = "bearerAuth")
public class DashboardController {
    private final MomDashboardService momDashboardService;
    private final PartnerDashboardService partnerDashboardService;

    public DashboardController(MomDashboardService momDashboardService,
                               PartnerDashboardService partnerDashboardService) {
        this.momDashboardService = momDashboardService;
        this.partnerDashboardService = partnerDashboardService;
    }

    @GetMapping("/mom")
    @Operation(summary = "Get the authenticated pregnancy owner's dashboard")
    public ApiResponse<MomDashboardResponse> mom(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(momDashboardService.getDashboard(jwt.getSubject()));
    }

    @GetMapping("/partner")
    @Operation(summary = "Get the scoped dashboard for a family member")
    public ApiResponse<PartnerDashboardResponse> partner(
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(
                partnerDashboardService.getDashboard(jwt.getSubject()));
    }
}
