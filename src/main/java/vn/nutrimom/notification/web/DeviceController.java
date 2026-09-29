package vn.nutrimom.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.notification.dto.NotificationDtos.DeviceRegistration;
import vn.nutrimom.notification.dto.NotificationDtos.DeviceResponse;
import vn.nutrimom.notification.dto.NotificationDtos.RegisterDeviceRequest;
import vn.nutrimom.notification.service.DeviceService;

@Validated
@RestController
@RequestMapping("/api/v1/devices")
@Tag(name = "Devices", description = "Đăng ký push token của thiết bị để nhận thông báo")
@SecurityRequirement(name = "bearerAuth")
public class DeviceController {
    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Đăng ký hoặc cập nhật push token của thiết bị; upsert theo device_id")
    public ResponseEntity<ApiResponse<DeviceResponse>> register(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RegisterDeviceRequest request) {
        DeviceRegistration result = service.register(jwt.getSubject(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponses.success(result.device()));
    }

    @DeleteMapping("/{deviceId}")
    @Operation(summary = "Gỡ đăng ký push khi đăng xuất khỏi thiết bị")
    public ResponseEntity<Void> unregister(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String deviceId) {
        service.unregister(jwt.getSubject(), deviceId);
        return ResponseEntity.noContent().build();
    }
}
