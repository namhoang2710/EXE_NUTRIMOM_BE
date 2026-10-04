package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.*;

public record LoginRequest(
        @NotBlank(message = "Email hoặc số điện thoại không được để trống")
        @Size(max = 100, message = "Email hoặc số điện thoại tối đa 100 ký tự") String phone,
        @NotBlank(message = "Mật khẩu không được để trống")
        @Size(max = 72, message = "Mật khẩu tối đa 72 ký tự") String password,
        @Size(max = 100, message = "Mã thiết bị tối đa 100 ký tự") String deviceId
) { }
