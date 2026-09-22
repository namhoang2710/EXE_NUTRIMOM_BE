package vn.nutrimom.consultation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import vn.nutrimom.consultation.domain.AssignmentType;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.consultation.dto.ReviewDtos.ReviewResponse;

/** DTO cho yêu cầu tư vấn (user tạo/theo dõi; chuyên gia và admin xử lý). */
public final class RequestDtos {
    private RequestDtos() {
    }

    /**
     * User tạo yêu cầu tư vấn.
     * <ul>
     *   <li>DIRECT: bắt buộc {@code expertUserId} + {@code slotId}.</li>
     *   <li>RANDOM: bắt buộc {@code specialty}; không chọn slot.</li>
     * </ul>
     * Ràng buộc chéo được kiểm tra ở service.
     */
    public record CreateConsultationRequest(
            @NotNull(message = "Thiếu hình thức chọn chuyên gia (DIRECT/RANDOM).")
            AssignmentType assignmentType,
            String expertUserId,
            String slotId,
            Specialty specialty,
            @Size(max = 2000, message = "Ghi chú quá dài.") String note) {
    }

    /** Chuyên gia nhận một yêu cầu RANDOM và xếp vào một slot trống của mình. */
    public record AcceptConsultationRequest(
            @NotBlank(message = "Thiếu khung giờ để xếp lịch.") String slotId) {
    }

    /** Thông tin khung giờ gắn với yêu cầu (null khi chưa có lịch). */
    public record SlotInfo(
            String id,
            LocalDate slotDate,
            LocalTime startTime,
            LocalTime endTime) {
    }

    /** Yêu cầu tư vấn theo góc nhìn user/chuyên gia. */
    public record ConsultationRequestResponse(
            String id,
            String userId,
            String userDisplayName,
            String expertUserId,
            String expertName,
            Specialty specialty,
            AssignmentType assignmentType,
            ConsultationStatus status,
            SlotInfo slot,
            String note,
            boolean reviewed,
            boolean canReview,
            OffsetDateTime completedAt,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /** Yêu cầu tư vấn theo góc nhìn admin: kèm user, chuyên gia và đánh giá đầy đủ. */
    public record AdminConsultationResponse(
            String id,
            String userId,
            String userDisplayName,
            String expertUserId,
            String expertName,
            Specialty specialty,
            AssignmentType assignmentType,
            ConsultationStatus status,
            SlotInfo slot,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt,
            ReviewResponse review) {
    }
}
