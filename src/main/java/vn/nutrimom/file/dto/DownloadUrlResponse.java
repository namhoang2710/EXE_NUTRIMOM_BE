package vn.nutrimom.file.dto;

import java.time.OffsetDateTime;

public record DownloadUrlResponse(
        String fileId,
        String downloadUrl,
        OffsetDateTime expiresAt) { }
