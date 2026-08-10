package vn.nutrimom.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.nutrimom.common.exception.BusinessException;

@Component
public class PhoneNormalizer {
    public String normalizeVietnamesePhone(String input) {
        String compact = input == null ? "" : input.replaceAll("[\\s.()-]", "");
        if (compact.matches("0[35789]\\d{8}")) return "+84" + compact.substring(1);
        if (compact.matches("\\+84[35789]\\d{8}")) return compact;
        if (compact.matches("84[35789]\\d{8}")) return "+" + compact;
        throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_PHONE",
                "Số điện thoại Việt Nam không đúng định dạng.");
    }
}
