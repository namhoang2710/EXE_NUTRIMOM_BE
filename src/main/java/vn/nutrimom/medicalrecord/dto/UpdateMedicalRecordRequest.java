package vn.nutrimom.medicalrecord.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import vn.nutrimom.medicalrecord.domain.MedicalRecordCategory;

public record UpdateMedicalRecordRequest(
        MedicalRecordCategory category,
        @Size(max = 255) String title,
        OffsetDateTime occurredAt,
        @Size(max = 255) String facilityName,
        @Size(max = 255) String clinicianName,
        String summary,
        String note,
        List<String> attachmentIds,
        @NotNull(message = "Version is required") Long version) { }
