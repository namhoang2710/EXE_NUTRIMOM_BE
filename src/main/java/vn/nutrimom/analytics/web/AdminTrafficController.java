package vn.nutrimom.analytics.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.analytics.dto.TrafficDtos.TrafficSummaryResponse;
import vn.nutrimom.analytics.service.TrafficAnalyticsService;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/admin/analytics")
@Tag(name = "Admin Analytics", description = "Admin traffic attribution & social marketing metrics")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTrafficController {

    private final TrafficAnalyticsService trafficAnalyticsService;

    public AdminTrafficController(TrafficAnalyticsService trafficAnalyticsService) {
        this.trafficAnalyticsService = trafficAnalyticsService;
    }

    @GetMapping("/traffic-summary")
    @Operation(summary = "Get aggregated traffic, signups, and conversion metrics across TikTok, FB, IG, and other channels")
    public ApiResponse<TrafficSummaryResponse> getTrafficSummary(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ApiResponses.success(trafficAnalyticsService.getTrafficSummary(from, to));
    }
}
