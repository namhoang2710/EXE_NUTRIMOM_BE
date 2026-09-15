package vn.nutrimom.auth.service;

import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

public class OtpVerificationException extends BusinessException {
    public OtpVerificationException(ErrorCode code, String message) {
        super(code, message);
    }
}
