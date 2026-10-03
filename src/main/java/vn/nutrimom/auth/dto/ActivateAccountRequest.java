package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ActivateAccountRequest(
        @NotBlank(message = "Mã kích hoạt không được để trống") String token,
        String email,
        String deviceId
) {
    public ActivateAccountRequest(String token, String deviceId) {
        this(token, null, deviceId);
    }
}
