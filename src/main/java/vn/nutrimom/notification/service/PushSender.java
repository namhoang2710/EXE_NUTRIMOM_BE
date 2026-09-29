package vn.nutrimom.notification.service;

/**
 * Cổng gửi push ra nhà cung cấp bên ngoài, tách khỏi phần lưu thông báo in-app.
 *
 * <p>Cùng vai trò với {@code OtpDeliveryGateway} của module auth: bản mặc định
 * {@link LoggingPushSender} chỉ ghi log để chạy được local và trong test; khi có Firebase thì thêm
 * một {@code FcmPushSender} implement interface này và đánh dấu {@code @Primary} — phần còn lại của
 * module notification không phải đổi.</p>
 *
 * <p>Được gọi <em>sau khi transaction commit</em>, nên lỗi gửi push không được phép làm rollback
 * việc đã ghi thông báo: mọi implement phải tự nuốt lỗi mạng của mình.</p>
 */
public interface PushSender {

    void send(PushMessage message);
}
