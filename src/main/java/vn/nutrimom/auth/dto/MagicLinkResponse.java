package vn.nutrimom.auth.dto;

public record MagicLinkResponse(
        boolean sent,
        String message,
        String debugLink,
        String debugCode
) { }
