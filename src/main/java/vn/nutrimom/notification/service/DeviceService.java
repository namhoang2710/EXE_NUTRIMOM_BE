package vn.nutrimom.notification.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.notification.domain.PushDeviceEntity;
import vn.nutrimom.notification.dto.NotificationDtos.DeviceRegistration;
import vn.nutrimom.notification.dto.NotificationDtos.DeviceResponse;
import vn.nutrimom.notification.dto.NotificationDtos.RegisterDeviceRequest;
import vn.nutrimom.notification.repository.PushDeviceRepository;

/** Đăng ký và gỡ push token của thiết bị (spec mục 18 "POST /devices"). */
@Service
public class DeviceService {

    private final PushDeviceRepository devices;
    private final PushTokenCipher cipher;

    public DeviceService(PushDeviceRepository devices, PushTokenCipher cipher) {
        this.devices = devices;
        this.cipher = cipher;
    }

    /**
     * Upsert theo {@code (userId, deviceId)}: cài lại app hay mở app hằng ngày đều gọi endpoint này,
     * nên gọi nhiều lần không được đẻ thêm dòng.
     *
     * <p>Thêm một bước dọn: nếu chính push token đó đang gắn với một dòng khác (thường là tài khoản
     * cũ trên cùng máy) thì gỡ dòng kia trước. Không làm vậy thì unique index trên hash sẽ chặn, và
     * tệ hơn là thiết bị có thể nhận push của tài khoản đã đăng xuất.</p>
     */
    @Transactional
    public DeviceRegistration register(String userId, RegisterDeviceRequest request) {
        String plainToken = request.pushToken().trim();
        String tokenHash = cipher.hash(plainToken);
        String deviceId = request.deviceId().trim();

        releaseTokenFromOtherDevices(userId, deviceId, tokenHash);

        PushDeviceEntity device = devices.findByUserIdAndDeviceId(userId, deviceId).orElse(null);
        boolean created = device == null;
        if (created) {
            device = new PushDeviceEntity();
            device.setUserId(userId);
            device.setDeviceId(deviceId);
        }
        device.setPlatform(request.platform());
        device.setPushTokenCipher(cipher.encrypt(plainToken));
        device.setPushTokenHash(tokenHash);
        device.setAppVersion(trimToNull(request.appVersion()));
        device.setActive(true);
        device.setLastSeenAt(OffsetDateTime.now(ZoneOffset.UTC));
        return new DeviceRegistration(toResponse(devices.saveAndFlush(device)), created);
    }

    /**
     * Gỡ đăng ký khi đăng xuất. Idempotent: thiết bị không tồn tại thì coi như đã gỡ, không báo lỗi,
     * vì client vẫn phải đăng xuất được kể cả khi chưa từng đăng ký push.
     */
    @Transactional
    public void unregister(String userId, String deviceId) {
        devices.findByUserIdAndDeviceId(userId, deviceId).ifPresent(devices::delete);
    }

    private void releaseTokenFromOtherDevices(String userId, String deviceId, String tokenHash) {
        devices.findByPushTokenHash(tokenHash)
                .filter(existing -> !existing.getUserId().equals(userId)
                        || !existing.getDeviceId().equals(deviceId))
                .ifPresent(devices::delete);
        devices.flush();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static DeviceResponse toResponse(PushDeviceEntity device) {
        return new DeviceResponse(device.getId(), device.getDeviceId(), device.getPlatform(),
                device.getAppVersion(), device.getLastSeenAt());
    }
}
