package vn.nutrimom.consultation.domain;

/**
 * Lý do một khung giờ không đặt được, theo góc nhìn user. API trả đủ 24 ô mỗi ngày kèm lý do
 * để FE hiện ô xám có nhãn thay vì ẩn đi — user hiểu vì sao chứ không tưởng ứng dụng lỗi.
 */
public enum UnavailableReason {
    /** Khung giờ đã trôi qua (chỉ xảy ra với ngày hôm nay). */
    PAST,
    /** Chuyên gia nghỉ cả ngày hôm đó. */
    DAY_OFF,
    /** Đã có người khác đặt. */
    BOOKED,
    /** Chuyên gia đã đóng riêng khung giờ này. */
    CLOSED
}
