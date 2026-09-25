package vn.nutrimom.contact.domain;

/**
 * Vòng đời một yêu cầu hỗ trợ:
 * <ul>
 *   <li>{@code PENDING} — user vừa gửi, chờ admin gọi điện giải đáp.</li>
 *   <li>{@code COMPLETED} — admin đã gọi và đánh dấu hoàn tất.</li>
 *   <li>{@code CANCELLED} — user tự huỷ khi còn đang chờ.</li>
 * </ul>
 */
public enum ContactRequestStatus {
    PENDING,
    COMPLETED,
    CANCELLED
}
