package vn.nutrimom.medicalrecord.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record MedicalRecordResponse(
        String id,
        String pregnancyId,
        String category,
        String title,
        OffsetDateTime occurredAt,
        String facilityName,
        String clinicianName,
        String summary,
        String note,
        int attachmentCount,
        List<MedicalRecordAttachmentResponse> attachments,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) { }
