package vn.nutrimom.consultation.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Tiện ích thời gian cho module tư vấn. Slot dùng {@code LocalDate}/{@code LocalTime}
 * hiểu theo giờ Việt Nam; helper này quy đổi nhất quán để so sánh với "hiện tại"
 * và dựng biên ngày cho bộ lọc.
 */
final class ConsultationClock {
    static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private ConsultationClock() {
    }

    /** "Bây giờ" theo giờ Việt Nam. */
    static LocalDateTime nowVietnam(Clock clock) {
        return LocalDateTime.ofInstant(clock.instant(), VIETNAM_ZONE);
    }

    /** Đầu ngày (00:00 giờ VN) của {@code date}, dạng OffsetDateTime để so với created_at. */
    static OffsetDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
    }

    /** Đầu ngày kế tiếp (biên trên loại trừ) của {@code date}. */
    static OffsetDateTime startOfNextDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
    }
}
