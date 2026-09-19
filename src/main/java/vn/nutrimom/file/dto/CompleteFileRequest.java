package vn.nutrimom.file.dto;

import jakarta.validation.constraints.Pattern;

public record CompleteFileRequest(
        @Pattern(regexp = "^[A-Fa-f0-9]{64}$", message = "sha256 must be a SHA-256 hex digest") String sha256,
        Long sizeBytes) { }
