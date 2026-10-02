package vn.nutrimom.calendar.domain;

/** Trạng thái một nhắc nhở người dùng tự tạo. */
public enum ReminderStatus {
    /** Đang chờ tới hạn; chỉ trạng thái này được job nhắc lịch quét. */
    SCHEDULED,
    /** Đã đi khám/đã làm. Đánh dấu DONE là cách v1 thay cho recurrence: response gợi ý mốc kế tiếp. */
    DONE,
    /** Người dùng bỏ mốc này nhưng vẫn muốn giữ lại trong lịch sử (khác với xoá mềm). */
    CANCELLED
}
