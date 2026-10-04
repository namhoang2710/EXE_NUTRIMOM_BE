package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.Size;

public record MagicLinkVerifyRequest(
        String token,
        String code,
        String email,
        @Size(max = 100, message = "Mã thiết bị tối đa 100 ký tự")
        String deviceId
) {
    public String resolveCodeOrToken() {
        if (code != null && !code.isBlank()) {
            return code.trim();
        }
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        return null;
    }
}
