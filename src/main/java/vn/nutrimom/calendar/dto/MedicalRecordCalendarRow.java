package vn.nutrimom.calendar.dto;

import java.time.OffsetDateTime;
import vn.nutrimom.medicalrecord.domain.MedicalRecordCategory;

/**
 * Một hồ sơ y tế đã rút gọn để vẽ lên lịch.
 *
 * <p>Record top-level (không phải lớp lồng) vì nó là đích của constructor expression trong HQL —
 * tên đầy đủ phải viết được gọn trong câu query.</p>
 */
public record MedicalRecordCalendarRow(
        String recordId,
        String title,
        OffsetDateTime occurredAt,
        MedicalRecordCategory category,
        String facilityName) {
}
