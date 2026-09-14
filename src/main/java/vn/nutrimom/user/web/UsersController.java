package vn.nutrimom.user.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.user.dto.UpdateProfileRequest;
import vn.nutrimom.user.dto.UserProfileResponse;
import vn.nutrimom.user.dto.DeleteAccountRequest;
import vn.nutrimom.user.dto.DeleteAccountResponse;
import vn.nutrimom.user.service.UserAccountService;
import vn.nutrimom.user.service.UserProfileService;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "User profile", description = "Hồ sơ và trạng thái onboarding của người dùng hiện tại")
@SecurityRequirement(name = "bearerAuth")
public class UsersController {
    private final UserProfileService profileService;
    private final UserAccountService accountService;

    public UsersController(UserProfileService profileService, UserAccountService accountService) {
        this.profileService = profileService;
        this.accountService = accountService;
    }

    @GetMapping("/me")
    @Operation(summary = "Lấy hồ sơ đầy đủ của người dùng hiện tại")
    public ApiResponse<UserProfileResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(profileService.getProfile(jwt.getSubject()));
    }

    @PatchMapping("/me")
    @Operation(summary = "Cập nhật hồ sơ người dùng hiện tại (optimistic locking theo version)")
    public ApiResponse<UserProfileResponse> update(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponses.success(profileService.updateProfile(jwt.getSubject(), request));
    }

    @DeleteMapping("/me")
    @Operation(summary = "Disable the current account after password or OTP reauthentication")
    public ResponseEntity<ApiResponse<DeleteAccountResponse>> delete(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DeleteAccountRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponses.success(accountService.deleteAccount(jwt.getSubject(), request)));
    }
}
