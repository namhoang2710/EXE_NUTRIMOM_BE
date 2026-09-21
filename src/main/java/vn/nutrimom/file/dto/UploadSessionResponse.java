package vn.nutrimom.file.dto;

import java.time.OffsetDateTime;
import java.util.Map;

public record UploadSessionResponse(
        String fileId,
        String uploadUrl,
        Map<String, String> headers,
        OffsetDateTime expiresAt) { }
