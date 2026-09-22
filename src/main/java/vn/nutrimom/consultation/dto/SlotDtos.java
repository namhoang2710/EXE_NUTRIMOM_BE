package vn.nutrimom.consultation.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import vn.nutrimom.consultation.domain.SlotStatus;

/** DTO cho khung giờ trống của chuyên gia. */
public final class SlotDtos {
    private SlotDtos() {
    }

    /** Chuyên gia mở một khung giờ trống. */
    public record CreateSlotRequest(
            @NotNull(message = "Thiếu ngày.")
            @FutureOrPresent(message = "Ngày không được ở quá khứ.")
            LocalDate slotDate,
            @NotNull(message = "Thiếu giờ bắt đầu.") LocalTime startTime,
            @NotNull(message = "Thiếu giờ kết thúc.") LocalTime endTime) {
    }

    /** Khung giờ hiển thị cho user (BOOKED thì không chọn được). */
    public record SlotResponse(
            String id,
            String expertUserId,
            LocalDate slotDate,
            LocalTime startTime,
            LocalTime endTime,
            SlotStatus status) {
    }
}
