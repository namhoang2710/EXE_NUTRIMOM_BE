package vn.nutrimom.consultation.domain;

/**
 * Trạng thái của một dòng trong {@code consultation_slots}. Dòng chỉ tồn tại khi ô đã bị
 * chiếm; không có dòng nghĩa là ô còn trống (xem {@link SlotGrid}).
 */
public enum SlotStatus {
    /** User đã đặt khung giờ này. */
    BOOKED,
    /** Chuyên gia đã đóng khung giờ này. */
    CLOSED
}
