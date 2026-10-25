package vn.nutrimom.auth.domain;

public enum PasswordResetTokenStatus {
    PENDING,
    USED,
    /** Bị thay bởi token mới hơn, hoặc huỷ khi mật khẩu đã được đặt lại bằng token khác. */
    REVOKED
}
