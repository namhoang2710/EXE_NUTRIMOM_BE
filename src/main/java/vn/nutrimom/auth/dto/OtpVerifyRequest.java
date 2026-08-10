package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.*;

public record OtpVerifyRequest(
        @NotBlank(message = "Challenge ID không được để trống") String challengeId,
        @NotBlank(message = "Mã OTP không được để trống")
        @Pattern(regexp = "\\d{6}", message = "Mã OTP phải gồm đúng 6 chữ số") String code,
        @Size(max = 100, message = "Mã thiết bị tối đa 100 ký tự") String deviceId,
        @Size(max = 100, message = "Tên hiển thị tối đa 100 ký tự") String displayName
) { }
