package vn.nutrimom.auth.service;

import org.springframework.http.HttpStatus;
import vn.nutrimom.common.exception.BusinessException;

public class OtpVerificationException extends BusinessException {
    public OtpVerificationException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
