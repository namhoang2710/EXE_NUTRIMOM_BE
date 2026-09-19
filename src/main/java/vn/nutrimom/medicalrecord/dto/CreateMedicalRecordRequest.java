package vn.nutrimom.medicalrecord.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import vn.nutrimom.medicalrecord.domain.MedicalRecordCategory;

public record CreateMedicalRecordRequest(
        @NotBlank(message = "pregnancy_id is required") String pregnancyId,
        @NotNull(message = "category is required") MedicalRecordCategory category,
        @NotBlank(message = "title is required") @Size(max = 255) String title,
        @NotNull(message = "occurred_at is required") OffsetDateTime occurredAt,
        @Size(max = 255) String facilityName,
        @Size(max = 255) String clinicianName,
        String summary,
        String note,
        List<String> attachmentIds) { }
