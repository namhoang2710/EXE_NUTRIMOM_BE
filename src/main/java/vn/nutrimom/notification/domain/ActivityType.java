package vn.nutrimom.notification.domain;

/**
 * Loại hoạt động hiển thị trên activity feed (spec mục 18 "GET /activity-feed").
 *
 * <p>Tên loại chỉ mô tả "việc gì đã xảy ra", không mang dữ liệu. Nội dung hiển thị nằm ở
 * {@code title} đã render sẵn từ template an toàn.</p>
 */
public enum ActivityType {
    CONSULTATION_ACCEPTED,
    CONSULTATION_COMPLETED,
    CONSULTATION_CANCELLED,
    CONTACT_COMPLETED,
    FAMILY_TASK_ASSIGNED,
    FAMILY_TASK_COMPLETED
}
