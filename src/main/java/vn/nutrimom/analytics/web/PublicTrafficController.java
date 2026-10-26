package vn.nutrimom.analytics.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.nutrimom.analytics.dto.TrafficDtos.RecordVisitRequest;
import vn.nutrimom.analytics.dto.TrafficDtos.RecordVisitResponse;
import vn.nutrimom.analytics.service.TrafficAnalyticsService;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;

@RestController
@RequestMapping("/api/v1/analytics/traffic")
@Tag(name = "Traffic Tracking", description = "Public endpoints for marketing channel and visit analytics")
public class PublicTrafficController {

    private final TrafficAnalyticsService trafficAnalyticsService;

    public PublicTrafficController(TrafficAnalyticsService trafficAnalyticsService) {
        this.trafficAnalyticsService = trafficAnalyticsService;
    }

    @PostMapping("/visit")
    @Operation(summary = "Record page visit from marketing channel (TikTok, FB, IG, Google, etc.)")
    public ApiResponse<RecordVisitResponse> recordVisit(
            @Valid @RequestBody RecordVisitRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest
    ) {
        String clientIp = resolveClientIp(servletRequest);
        String userAgent = servletRequest.getHeader("User-Agent");
        String currentUserId = jwt != null ? jwt.getSubject() : null;

        RecordVisitResponse response = trafficAnalyticsService.recordVisit(request, clientIp, userAgent, currentUserId);
        return ApiResponses.success(response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
