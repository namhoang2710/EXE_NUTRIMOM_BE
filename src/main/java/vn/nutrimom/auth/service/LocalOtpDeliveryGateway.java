package vn.nutrimom.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.config.OtpProperties;

@Component
public class LocalOtpDeliveryGateway implements OtpDeliveryGateway {
    private final OtpProperties properties;
    public LocalOtpDeliveryGateway(OtpProperties properties) { this.properties = properties; }

    @Override
    public String deliver(String phone, String code) {
        if (!properties.isExposeDebugCode()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OTP_PROVIDER_NOT_CONFIGURED", "Nhà cung cấp SMS OTP chưa được cấu hình.", true);
        }
        return "DEBUG";
    }
}
