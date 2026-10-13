package vn.nutrimom.common.validation;

/** Quy tắc mật khẩu dùng chung cho mọi luồng đặt mật khẩu. */
public final class PasswordPolicy {
    /** Bắt buộc có chữ thường, chữ hoa, chữ số, ký tự đặc biệt; không chứa khoảng trắng. */
    public static final String PATTERN =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S{8,20}$";
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 20;
    public static final String LENGTH_MESSAGE = "Mật khẩu phải dài từ 8 đến 20 ký tự";
    public static final String MESSAGE =
            "Mật khẩu phải có chữ hoa, chữ thường, chữ số và ký tự đặc biệt, không chứa khoảng trắng";

    private PasswordPolicy() { }
}
