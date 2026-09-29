package vn.nutrimom.notification.service;

import vn.nutrimom.notification.domain.DevicePlatform;

/**
 * Một bản push gửi tới đúng một thiết bị.
 *
 * <p>{@code token} là push token đã giải mã — chỉ tồn tại trong bộ nhớ lúc gửi, không được ghi log
 * hay đưa vào message của exception (spec mục 21 "Không log token").</p>
 */
public record PushMessage(
        String token,
        DevicePlatform platform,
        String title,
        String body,
        String deepLink) {
}
