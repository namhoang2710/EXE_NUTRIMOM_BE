package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendActivationRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Định dạng email chưa hợp lệ") String email
) { }
