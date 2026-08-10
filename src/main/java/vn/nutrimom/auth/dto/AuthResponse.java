package vn.nutrimom.auth.dto;

public record AuthResponse(String accessToken, long expiresIn, String refreshToken,
                           long refreshExpiresIn, String tokenType, UserResponse user) { }
