package vn.nutrimom.consultation.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Lưới khung giờ mặc định của mọi chuyên gia: 08:00–20:00, mỗi khung 30 phút.
 *
 * <p>Chuyên gia không cần mở lịch: ngày nào cũng mặc định có đủ các khung giờ này.
 * Bảng {@code consultation_slots} chỉ lưu những ô đã bị chiếm (BOOKED) hoặc bị chuyên gia
 * đóng (CLOSED) — không có dòng nghĩa là ô còn trống.
 */
public final class SlotGrid {
    /** Giờ mở cửa (khung đầu tiên bắt đầu đúng mốc này). */
    public static final LocalTime OPENING = LocalTime.of(8, 0);
    /** Giờ đóng cửa (khung cuối cùng kết thúc đúng mốc này). */
    public static final LocalTime CLOSING = LocalTime.of(20, 0);
    /** Độ dài một khung giờ, tính bằng phút. */
    public static final int SLOT_MINUTES = 30;
    /** User đặt lịch được tối đa bao nhiêu ngày tính từ hôm nay. */
    public static final int HORIZON_DAYS = 30;

    private static final List<LocalTime> START_TIMES = buildStartTimes();

    private SlotGrid() {
    }

    /** Các mốc bắt đầu trong một ngày, tăng dần (08:00, 08:30, … 19:30). */
    public static List<LocalTime> startTimes() {
        return START_TIMES;
    }

    /** {@code true} khi {@code value} đúng một mốc trong lưới. */
    public static boolean isValidStart(LocalTime value) {
        return value != null && START_TIMES.contains(value);
    }

    /** Giờ kết thúc của khung bắt đầu lúc {@code start}. */
    public static LocalTime endOf(LocalTime start) {
        return start.plusMinutes(SLOT_MINUTES);
    }

    /** {@code true} khi {@code date} nằm trong khoảng đặt lịch cho phép (tính theo giờ VN). */
    public static boolean isWithinHorizon(LocalDate date, LocalDate todayVietnam) {
        return date != null
                && !date.isBefore(todayVietnam)
                && !date.isAfter(todayVietnam.plusDays(HORIZON_DAYS));
    }

    private static List<LocalTime> buildStartTimes() {
        List<LocalTime> times = new ArrayList<>();
        for (LocalTime start = OPENING; start.isBefore(CLOSING);
                start = start.plusMinutes(SLOT_MINUTES)) {
            times.add(start);
        }
        return List.copyOf(times);
    }
}
