package vn.nutrimom.auth.service;

import org.springframework.stereotype.Component;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.config.OtpProperties;

@Component
public class LocalOtpDeliveryGateway implements OtpDeliveryGateway {
    private final OtpProperties properties;
    public LocalOtpDeliveryGateway(OtpProperties properties) { this.properties = properties; }

    @Override
    public String deliver(String phone, String code) {
        if (!properties.isExposeDebugCode()) {
            throw new BusinessException(ErrorCode.OTP_PROVIDER_NOT_CONFIGURED,
                    "Nhà cung cấp SMS OTP chưa được cấu hình.");
        }
        return "DEBUG";
    }
}
