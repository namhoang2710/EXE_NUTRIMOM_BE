package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.*;

public record MagicLinkRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Định dạng email chưa hợp lệ")
        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email,

        @Size(max = 100, message = "Mã thiết bị tối đa 100 ký tự")
        String deviceId
) { }
