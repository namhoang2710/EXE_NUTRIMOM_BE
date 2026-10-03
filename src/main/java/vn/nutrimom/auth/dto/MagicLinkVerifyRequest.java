package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.*;

public record MagicLinkVerifyRequest(
        @NotBlank(message = "Mã token không được để trống")
        String token,

        @Size(max = 100, message = "Mã thiết bị tối đa 100 ký tự")
        String deviceId
) { }
