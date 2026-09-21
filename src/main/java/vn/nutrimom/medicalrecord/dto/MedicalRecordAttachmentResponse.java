package vn.nutrimom.medicalrecord.dto;

public record MedicalRecordAttachmentResponse(
        String id,
        String fileName,
        String mimeType,
        long sizeBytes,
        String status) { }
