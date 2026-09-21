package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank(message = "Số điện thoại không được để trống")
        @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự") String phone,
        @NotBlank(message = "Mật khẩu không được để trống")
        @Size(min = 8, max = 72, message = "Mật khẩu phải dài từ 8 đến 72 ký tự")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "Mật khẩu phải có ít nhất một chữ cái và một chữ số") String password,
        @NotBlank(message = "Tên hiển thị không được để trống")
        @Size(max = 100, message = "Tên hiển thị tối đa 100 ký tự") String displayName,
        @NotNull(message = "Bạn cần đồng ý Điều khoản sử dụng và Chính sách bảo mật.")
        @AssertTrue(message = "Bạn cần đồng ý Điều khoản sử dụng và Chính sách bảo mật.")
        Boolean acceptedTerms,
        @Size(max = 100, message = "Mã thiết bị tối đa 100 ký tự") String deviceId
) { }
