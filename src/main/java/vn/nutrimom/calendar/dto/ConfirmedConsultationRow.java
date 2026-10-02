package vn.nutrimom.calendar.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import vn.nutrimom.consultation.domain.ConsultationStatus;

/**
 * Một buổi tư vấn đã có khung giờ (tức chuyên gia đã xác nhận), rút gọn để vẽ lên lịch.
 *
 * <p>{@code slotDate}/{@code startTime}/{@code endTime} là giá trị <em>naive</em> mang nghĩa giờ
 * Việt Nam theo quy ước của module tư vấn — phải quy đổi qua
 * {@code CalendarZone#VIETNAM_ZONE} trước khi so với cửa sổ truy vấn.</p>
 */
public record ConfirmedConsultationRow(
        String requestId,
        ConsultationStatus status,
        LocalDate slotDate,
        LocalTime startTime,
        LocalTime endTime,
        String expertFullName) {
}
