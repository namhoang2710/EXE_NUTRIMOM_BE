package vn.nutrimom.auth.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.auth.dto.PasswordResetDtos.ChangePasswordRequest;
import vn.nutrimom.auth.dto.PasswordResetDtos.ForgotPasswordRequest;
import vn.nutrimom.auth.dto.PasswordResetDtos.ForgotPasswordResponse;
import vn.nutrimom.auth.dto.PasswordResetDtos.ResetPasswordRequest;
import vn.nutrimom.auth.dto.PasswordResetDtos.ResetPasswordResponse;
import vn.nutrimom.auth.dto.PasswordResetDtos.ValidateResetTokenRequest;
import vn.nutrimom.auth.dto.PasswordResetDtos.ValidateResetTokenResponse;
import vn.nutrimom.auth.service.PasswordResetService;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;

/**
 * Quên mật khẩu (công khai) và đổi mật khẩu (đã đăng nhập).
 *
 * <p>Token đặt lại đi trong body chứ không trên URL của API, để không nằm trong log truy cập hay
 * header Referer.</p>
 */
@RestController
@RequestMapping("/api/v1/auth/password")
@Tag(name = "Auth", description = "Đăng ký, đăng nhập và mật khẩu")
public class PasswordController {
    private final PasswordResetService service;

    public PasswordController(PasswordResetService service) {
        this.service = service;
    }

    @PostMapping("/forgot")
    @Operation(summary = "Gửi email đặt lại mật khẩu; luôn trả cùng một câu dù email có tồn tại hay không")
    public ApiResponse<ForgotPasswordResponse> forgot(@Valid @RequestBody ForgotPasswordRequest request,
                                                      HttpServletRequest httpRequest) {
        return ApiResponses.success(service.requestReset(request, httpRequest.getRemoteAddr()));
    }

    @PostMapping("/reset/validate")
    @Operation(summary = "Kiểm tra link đặt lại mật khẩu còn dùng được không, trước khi hiện form")
    public ApiResponse<ValidateResetTokenResponse> validate(@Valid @RequestBody ValidateResetTokenRequest request) {
        return ApiResponses.success(service.validate(request));
    }

    @PostMapping("/reset")
    @Operation(summary = "Đặt mật khẩu mới bằng link trong email; đăng xuất mọi thiết bị, không tự đăng nhập")
    public ApiResponse<ResetPasswordResponse> reset(@Valid @RequestBody ResetPasswordRequest request) {
        return ApiResponses.success(service.reset(request));
    }

    @PostMapping("/change")
    @Operation(summary = "Đổi mật khẩu khi đang đăng nhập; cần mật khẩu hiện tại, đăng xuất mọi thiết bị")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> change(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        service.changePassword(jwt.getSubject(), request);
        return ResponseEntity.noContent().build();
    }
}
