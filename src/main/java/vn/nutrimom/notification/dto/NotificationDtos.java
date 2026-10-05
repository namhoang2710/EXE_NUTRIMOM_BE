package vn.nutrimom.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import vn.nutrimom.notification.domain.ActivityType;
import vn.nutrimom.notification.domain.DevicePlatform;
import vn.nutrimom.notification.domain.NotificationType;

/** DTO của module thông báo và activity feed (spec mục 18). */
public final class NotificationDtos {
    private NotificationDtos() {
    }

    /** Body của {@code POST /devices}; upsert theo {@code device_id} của chính người gọi. */
    public record RegisterDeviceRequest(
            @NotNull(message = "Vui lòng cho biết nền tảng của thiết bị.")
            DevicePlatform platform,
            @NotBlank(message = "Vui lòng gửi push token của thiết bị.")
            @Size(max = 500, message = "Push token tối đa 500 ký tự.")
            String pushToken,
            @NotBlank(message = "Vui lòng gửi device_id của thiết bị.")
            @Size(max = 100, message = "device_id tối đa 100 ký tự.")
            String deviceId,
            @Size(max = 30, message = "app_version tối đa 30 ký tự.")
            String appVersion) {
    }

    /**
     * Thiết bị đã đăng ký.
     *
     * <p>Cố ý <strong>không</strong> có trường push token, kể cả bản đã mã hoá: client vừa gửi token
     * lên nên không cần nhận lại, và trả về chỉ tạo thêm một đường rò (spec mục 21).</p>
     */
    public record DeviceResponse(
            String id,
            String deviceId,
            DevicePlatform platform,
            String appVersion,
            OffsetDateTime lastSeenAt) {
    }

    /** Kết quả upsert: {@code created} để controller chọn 201 (tạo mới) hay 200 (cập nhật). */
    public record DeviceRegistration(DeviceResponse device, boolean created) {
    }

    /** Một thông báo in-app. Các field theo đúng spec mục 18, cộng id và nguồn để điều hướng. */
    public record NotificationResponse(
            String id,
            NotificationType type,
            String title,
            String body,
            String deepLink,
            String sourceType,
            String sourceId,
            OffsetDateTime readAt,
            OffsetDateTime createdAt) {
    }

    /** Kết quả {@code POST /notifications/read-all}; gọi lại lần hai trả {@code updated = 0}. */
    public record ReadAllResponse(int updated) {
    }

    /**
     * Kết quả {@code GET /notifications/unread-count}; dùng cho chuông thông báo trên mọi trang.
     *
     * <p>Field cố ý đặt tên {@code count} chứ không phải {@code unreadCount}: Jackson của project
     * chạy SNAKE_CASE nên {@code unreadCount} sẽ serialize thành {@code unread_count}, trong khi
     * đường dẫn đã tên là {@code /unread-count} rồi nên {@code count} vừa gọn vừa không mơ hồ.</p>
     */
    public record UnreadCountResponse(long count) {
    }

    /**
     * Một dòng activity feed.
     *
     * <p>Chỉ có {@code title} đã render sẵn, không có body/preview — đúng yêu cầu "không làm rò dữ
     * liệu medical qua message preview" của spec mục 18.</p>
     */
    public record ActivityEventResponse(
            String id,
            ActivityType type,
            String title,
            String actorUserId,
            String pregnancyId,
            OffsetDateTime createdAt) {
    }
}
