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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.notification.dto.NotificationDtos.NotificationResponse;
import vn.nutrimom.notification.dto.NotificationDtos.ReadAllResponse;
import vn.nutrimom.notification.service.NotificationService;

@Validated
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Hộp thông báo in-app của người dùng")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Danh sách thông báo, mới nhất trước, phân trang bằng cursor")
    public ApiResponse<CursorPage<NotificationResponse>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return ApiResponses.success(service.list(jwt.getSubject(), cursor, limit, unreadOnly));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Đánh dấu một thông báo đã đọc; idempotent")
    public ApiResponse<NotificationResponse> read(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ApiResponses.success(service.markRead(jwt.getSubject(), id));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Đánh dấu toàn bộ thông báo đã đọc; idempotent, trả số dòng vừa đổi")
    public ApiResponse<ReadAllResponse> readAll(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(new ReadAllResponse(service.markAllRead(jwt.getSubject())));
    }
}
