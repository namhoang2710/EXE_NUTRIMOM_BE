package vn.nutrimom.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUploadSessionRequest(
        @NotBlank(message = "purpose is required") @Size(max = 50) String purpose,
        @NotBlank(message = "file_name is required") @Size(max = 255) String fileName,
        @NotBlank(message = "mime_type is required") @Size(max = 100) String mimeType,
        @NotNull(message = "size_bytes is required") @Positive(message = "size_bytes must be positive") Long sizeBytes,
        @NotBlank(message = "sha256 is required")
        @Pattern(regexp = "^[A-Fa-f0-9]{64}$", message = "sha256 must be a SHA-256 hex digest") String sha256,
        String pregnancyId,
        String medicalRecordId) { }
