package vn.nutrimom.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bản mặc định của {@link PushSender} dùng cho local và test: chỉ ghi lại việc "đã gửi" mà không gọi
 * ra ngoài, giống cách {@code LocalOtpDeliveryGateway} thay cho nhà cung cấp SMS thật.
 *
 * <p>Log cố ý chỉ ghi nền tảng và tiêu đề. Không ghi push token, không ghi {@code body} vì body có
 * thể chứa nội dung riêng tư (spec mục 21 "Không log token", mục 18 không rò dữ liệu qua preview).</p>
 */
@Component
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public void send(PushMessage message) {
        log.info("Push delivered platform={} title={}", message.platform(), message.title());
    }
}
