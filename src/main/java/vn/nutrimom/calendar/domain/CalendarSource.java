package vn.nutrimom.calendar.domain;

/**
 * Nguồn của một mốc trên lịch (spec mục 10).
 *
 * <p>Hai giá trị đầu chỉ được ĐỌC từ module khác, không có bảng riêng trong module calendar:
 * {@link #MEDICAL_RECORD} đọc từ {@code app.medical_records}, {@link #CONSULTATION} đọc từ
 * {@code app.consultation_requests} và chỉ khi chuyên gia đã xác nhận (đã có khung giờ).
 * {@link #REMINDER} là nguồn duy nhất người dùng tự ghi.</p>
 *
 * <p>Thứ tự khai báo chính là thứ tự {@code types[]} trả về ở {@code GET /calendar/month} —
 * giữ cố định để response ổn định giữa hai lần gọi.</p>
 */
public enum CalendarSource {
    MEDICAL_RECORD,
    CONSULTATION,
    REMINDER
}
