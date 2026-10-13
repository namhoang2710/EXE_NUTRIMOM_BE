package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.*;
import vn.nutrimom.common.validation.PasswordPolicy;

public record RegisterRequest(
        @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự") String phone,
        @Email(message = "Định dạng email chưa hợp lệ")
        @Size(max = 255, message = "Email tối đa 255 ký tự") String email,
        @NotBlank(message = "Mật khẩu không được để trống")
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                message = PasswordPolicy.LENGTH_MESSAGE)
        @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE) String password,
        @NotBlank(message = "Tên hiển thị không được để trống")
        @Size(max = 100, message = "Tên hiển thị tối đa 100 ký tự") String displayName,
        @NotNull(message = "Bạn cần đồng ý Điều khoản sử dụng và Chính sách bảo mật.")
        @AssertTrue(message = "Bạn cần đồng ý Điều khoản sử dụng và Chính sách bảo mật.")
        Boolean acceptedTerms,
        @Size(max = 100, message = "Mã thiết bị tối đa 100 ký tự") String deviceId
) { }
