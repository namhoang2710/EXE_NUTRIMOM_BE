package vn.nutrimom.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.notification.dto.NotificationDtos.ActivityEventResponse;
import vn.nutrimom.notification.service.ActivityFeedService;

@Validated
@RestController
@RequestMapping("/api/v1/activity-feed")
@Tag(name = "Activity feed", description = "Dòng hoạt động mà người gọi có quyền xem")
@SecurityRequirement(name = "bearerAuth")
public class ActivityFeedController {
    private final ActivityFeedService service;

    public ActivityFeedController(ActivityFeedService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Hoạt động của chính mình và hoạt động được chia sẻ từ nhóm gia đình")
    public ApiResponse<CursorPage<ActivityEventResponse>> feed(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return ApiResponses.success(service.feed(jwt.getSubject(), cursor, limit));
    }
}
