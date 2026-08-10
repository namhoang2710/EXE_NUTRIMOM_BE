package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.*;

public record RefreshRequest(
        @NotBlank(message = "Refresh token không được để trống") String refreshToken,
        @Size(max = 100, message = "Mã thiết bị tối đa 100 ký tự") String deviceId
) { }
