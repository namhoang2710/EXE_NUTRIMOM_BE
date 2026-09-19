package vn.nutrimom.file.dto;

import java.time.OffsetDateTime;

public record FileResponse(
        String id,
        String purpose,
        String fileName,
        String mimeType,
        long sizeBytes,
        String sha256,
        String status,
        String pregnancyId,
        String medicalRecordId,
        OffsetDateTime expiresAt,
        OffsetDateTime completedAt) { }
