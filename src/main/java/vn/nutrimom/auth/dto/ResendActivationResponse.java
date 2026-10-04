package vn.nutrimom.auth.dto;

public record ResendActivationResponse(
        boolean sent,
        String message
) { }
