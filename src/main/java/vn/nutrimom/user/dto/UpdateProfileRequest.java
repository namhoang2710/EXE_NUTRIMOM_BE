package vn.nutrimom.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import vn.nutrimom.auth.domain.Gender;

/**
 * Cập nhật hồ sơ người dùng (PATCH /users/me).
 * Các field hồ sơ đều optional (partial update); chỉ field khác null mới được ghi đè.
 * {@code version} bắt buộc để kiểm tra optimistic locking (409 VERSION_CONFLICT).
 */
public record UpdateProfileRequest(
        @Size(max = 100, message = "Tên hiển thị tối đa 100 ký tự")
        String displayName,

        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email,

        @Past(message = "Ngày sinh phải ở quá khứ")
        LocalDate dateOfBirth,

        Gender gender,

        @Size(max = 50, message = "avatar_key tối đa 50 ký tự")
        String avatarKey,

        @NotNull(message = "Thiếu version để kiểm tra xung đột")
        Long version) { }
