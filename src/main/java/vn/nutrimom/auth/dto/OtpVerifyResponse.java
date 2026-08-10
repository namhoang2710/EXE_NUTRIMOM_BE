package vn.nutrimom.auth.dto;

public record OtpVerifyResponse(boolean newUser, AuthResponse authentication) { }
