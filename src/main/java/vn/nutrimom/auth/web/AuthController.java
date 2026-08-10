package vn.nutrimom.auth.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.nutrimom.auth.dto.*;
import vn.nutrimom.auth.service.*;
import vn.nutrimom.common.api.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Đăng ký, OTP, đăng nhập và quản lý phiên JWT")
public class AuthController {
    private final AuthService authService;
    private final OtpService otpService;

    public AuthController(AuthService authService, OtpService otpService) {
        this.authService = authService;
        this.otpService = otpService;
    }

    @PostMapping("/otp/request")
    @Operation(summary = "Tạo OTP động cho đăng nhập hoặc đăng ký")
    public ApiResponse<OtpChallengeResponse> requestOtp(@Valid @RequestBody OtpChallengeRequest request,
                                                        HttpServletRequest httpRequest) {
        return ApiResponses.success(otpService.request(request, httpRequest.getRemoteAddr()));
    }

    @PostMapping("/otp/verify")
    @Operation(summary = "Xác minh OTP và tạo phiên đăng nhập")
    public ApiResponse<OtpVerifyResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        return ApiResponses.success(otpService.verify(request));
    }

    @PostMapping("/register")
    @Operation(summary = "Đăng ký tài khoản bằng số điện thoại và mật khẩu")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.success(authService.register(request)));
    }

    @PostMapping("/login")
    @Operation(summary = "Đăng nhập bằng mật khẩu và nhận token")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponses.success(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Đổi refresh token cũ lấy cặp token mới")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponses.success(authService.refresh(request));
    }

    @PostMapping("/logout")
    @Operation(summary = "Thu hồi refresh token; thao tác idempotent")
    public ApiResponse<LogoutResponse> logout(@Valid @RequestBody LogoutRequest request) {
        return ApiResponses.success(authService.logout(request));
    }

    @GetMapping("/me")
    @Operation(summary = "Lấy tài khoản hiện tại")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(authService.me(jwt.getSubject()));
    }
}
