package vn.nutrimom.community.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.community.dto.CommunityDtos.MarkAllReadResult;
import vn.nutrimom.community.dto.CommunityDtos.NotificationItem;
import vn.nutrimom.community.dto.CommunityDtos.UnreadCount;
import vn.nutrimom.community.service.CommunityNotificationService;

/** Thông báo cộng đồng — tách khỏi {@code /api/v1/notifications} của chuông header. */
@RestController
@RequestMapping("/api/v1/community/notifications")
@SecurityRequirement(name = "bearerAuth")
public class CommunityNotificationController {
    private final CommunityNotificationService service;

    public CommunityNotificationController(CommunityNotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<CursorPage<NotificationItem>> list(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "false") boolean unreadOnly,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.list(jwt.getSubject(), cursor, limit, unreadOnly));
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCount> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.unreadCount(jwt.getSubject()));
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Boolean> markRead(@PathVariable String notificationId, @AuthenticationPrincipal Jwt jwt) {
        service.markRead(jwt.getSubject(), notificationId);
        return ApiResponses.success(true);
    }

    @PostMapping("/read-all")
    public ApiResponse<MarkAllReadResult> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(service.markAllRead(jwt.getSubject()));
    }
}
