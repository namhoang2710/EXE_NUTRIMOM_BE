package vn.nutrimom.notification.domain;

/**
 * Ai được nhìn thấy một activity event.
 *
 * <p>Spec mục 18 yêu cầu activity feed "không làm rò dữ liệu medical qua message preview". Quy ước:
 * mọi hoạt động chạm tới y tế/tư vấn ghi {@link #OWNER_ONLY}; chỉ việc chung của nhóm gia đình mới
 * được {@link #FAMILY}.</p>
 */
public enum ActivityVisibility {
    /** Chỉ chính chủ thấy, kể cả thành viên gia đình có scope ACTIVITY_FEED cũng không thấy. */
    OWNER_ONLY,
    /** Thành viên gia đình có scope {@code ACTIVITY_FEED} của thai kỳ này cũng thấy. */
    FAMILY
}
