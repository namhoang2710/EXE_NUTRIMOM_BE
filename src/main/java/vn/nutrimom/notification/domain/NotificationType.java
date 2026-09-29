package vn.nutrimom.notification.domain;

/**
 * Nhóm thông báo in-app. App dùng giá trị này để chọn icon và màn hình đích kèm {@code deepLink}.
 *
 * <p>{@link #REMINDER} dành sẵn cho module calendar/reminders (spec mục 10) — job nhắc lịch chỉ cần gọi
 * {@code NotificationService.publish(...)} với type này, không phải sửa lại module notification.</p>
 */
public enum NotificationType {
    /** Yêu cầu tư vấn đổi trạng thái (chuyên gia tiếp nhận, hoàn tất, huỷ). */
    CONSULTATION,
    /** Yêu cầu hỗ trợ trong hộp thư Contact đã được admin xử lý. */
    CONTACT,
    /** Hoạt động trong nhóm gia đình (được giao việc...). */
    FAMILY,
    /** Nhắc lịch khám/tái khám theo calendar. */
    REMINDER,
    /** Thông báo hệ thống. */
    SYSTEM
}
