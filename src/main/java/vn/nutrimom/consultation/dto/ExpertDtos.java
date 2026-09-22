package vn.nutrimom.consultation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.domain.Specialty;

/** DTO cho hồ sơ chuyên gia: user xem, admin CRUD. */
public final class ExpertDtos {
    private ExpertDtos() {
    }

    /** Thông tin cơ bản cho danh sách "tìm chuyên gia". */
    public record ExpertSummaryResponse(
            String userId,
            String fullName,
            Specialty specialty,
            String title,
            String workplace,
            int yearsOfExperience,
            String avatarUrl,
            BigDecimal averageRating,
            int ratingCount) {
    }

    /** Chi tiết một chuyên gia (thêm bio). */
    public record ExpertDetailResponse(
            String userId,
            String fullName,
            Specialty specialty,
            String title,
            String workplace,
            int yearsOfExperience,
            String bio,
            String avatarUrl,
            BigDecimal averageRating,
            int ratingCount) {
    }

    /** Admin tạo chuyên gia + cấp tài khoản (phone/password để bàn giao cho chuyên gia). */
    public record CreateExpertRequest(
            @NotBlank(message = "Thiếu số điện thoại đăng nhập của chuyên gia.")
            String phone,
            @NotBlank(message = "Thiếu mật khẩu.")
            @Size(min = 8, max = 72, message = "Mật khẩu phải từ 8 đến 72 ký tự.")
            String password,
            @NotBlank(message = "Thiếu họ tên chuyên gia.")
            @Size(max = 100)
            String fullName,
            @NotNull(message = "Thiếu chuyên khoa.")
            Specialty specialty,
            @Size(max = 100) String title,
            @Size(max = 255) String workplace,
            @Min(value = 0, message = "Số năm kinh nghiệm không hợp lệ.")
            @Max(value = 80, message = "Số năm kinh nghiệm không hợp lệ.")
            int yearsOfExperience,
            @Size(max = 4000) String bio) {
    }

    /** Admin cập nhật hồ sơ chuyên gia (optimistic lock qua version). */
    public record UpdateExpertRequest(
            @Size(max = 100) String fullName,
            Specialty specialty,
            @Size(max = 100) String title,
            @Size(max = 255) String workplace,
            @Min(0) @Max(80) Integer yearsOfExperience,
            @Size(max = 4000) String bio,
            ExpertStatus status,
            @NotNull(message = "Thiếu version để cập nhật.") Long version) {
    }

    /** Chi tiết đầy đủ cho admin. */
    public record AdminExpertResponse(
            String userId,
            String phone,
            String fullName,
            Specialty specialty,
            String title,
            String workplace,
            int yearsOfExperience,
            String bio,
            String avatarKey,
            String avatarUrl,
            ExpertStatus status,
            BigDecimal averageRating,
            int ratingCount,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
