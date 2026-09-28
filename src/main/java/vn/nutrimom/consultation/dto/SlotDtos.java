package vn.nutrimom.consultation.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import vn.nutrimom.consultation.domain.SlotState;
import vn.nutrimom.consultation.domain.UnavailableReason;

/**
 * DTO cho lưới khung giờ. Mọi response đều trả ĐỦ các mốc trong ngày (xem
 * {@code SlotGrid}); ô không đặt được đi kèm lý do để FE hiện ô xám có nhãn thay vì ẩn đi.
 */
public final class SlotDtos {
    private SlotDtos() {
    }

    // ----- Góc nhìn user -----

    /** Một ô trong lưới; {@code reason} null khi ô còn đặt được. */
    public record AvailabilitySlot(
            LocalTime startTime,
            LocalTime endTime,
            boolean available,
            UnavailableReason reason) {
    }

    /** Lịch một ngày của chuyên gia theo góc nhìn user. */
    public record DayAvailabilityResponse(
            LocalDate date,
            boolean dayOff,
            List<AvailabilitySlot> slots) {
    }

    // ----- Góc nhìn chuyên gia -----

    /** Khách đã đặt một ô, hiện kèm trên lịch làm việc của chuyên gia. */
    public record BookingBrief(String requestId, String userDisplayName) {
    }

    /** Một ô trong lưới; {@code booking} chỉ khác null khi {@code state} là BOOKED. */
    public record ScheduleSlot(
            LocalTime startTime,
            LocalTime endTime,
            SlotState state,
            boolean past,
            BookingBrief booking) {
    }

    /** Lịch làm việc một ngày của chính chuyên gia. */
    public record DayScheduleResponse(
            LocalDate date,
            boolean dayOff,
            boolean hasBookings,
            List<ScheduleSlot> slots) {
    }

    /** Tổng hợp một ngày, dùng cho dải ngày điều hướng. */
    public record DaySummary(
            LocalDate date,
            int openCount,
            int bookedCount,
            int closedCount,
            boolean dayOff) {
    }

    /** Chuyên gia gạt đóng/mở một khung giờ. Idempotent: gửi lại cùng payload vô hại. */
    public record ToggleSlotRequest(
            @NotNull(message = "Thiếu ngày.") LocalDate slotDate,
            @NotNull(message = "Thiếu giờ bắt đầu.") LocalTime startTime,
            @NotNull(message = "Thiếu trạng thái đóng/mở.") Boolean closed) {
    }

    /** Chuyên gia bật/tắt nghỉ cả ngày. Idempotent. */
    public record ToggleDayOffRequest(
            @NotNull(message = "Thiếu ngày.") LocalDate slotDate,
            @NotNull(message = "Thiếu trạng thái nghỉ cả ngày.") Boolean dayOff) {
    }
}
