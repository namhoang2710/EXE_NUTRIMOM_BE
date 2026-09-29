package vn.nutrimom.notification.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.notification.domain.PushDeviceEntity;

public interface PushDeviceRepository extends JpaRepository<PushDeviceEntity, String> {

    /** Khóa upsert của {@code POST /devices}. */
    Optional<PushDeviceEntity> findByUserIdAndDeviceId(String userId, String deviceId);

    /**
     * Một push token chỉ được thuộc về một tài khoản. Dùng hash vì ciphertext không tất định
     * (IV ngẫu nhiên) nên không so sánh trực tiếp được.
     */
    Optional<PushDeviceEntity> findByPushTokenHash(String pushTokenHash);

    /** Các thiết bị còn nhận push của user, dùng khi fan-out một thông báo. */
    List<PushDeviceEntity> findByUserIdAndActiveTrue(String userId);
}
