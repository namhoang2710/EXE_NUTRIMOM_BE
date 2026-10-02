package vn.nutrimom.calendar.domain;

/**
 * Trạng thái của MỘT lần lặp cụ thể.
 *
 * <p>Không có giá trị "chưa làm": lần lặp nào người dùng chưa đụng tới thì không có dòng nào trong
 * {@code app.calendar_reminder_occurrences}. Bỏ đánh dấu = xoá dòng.</p>
 */
public enum OccurrenceStatus {
    /** Đã uống thuốc / đã đi khám hôm đó. */
    DONE,
    /** Cố ý bỏ qua hôm đó, không phải quên. */
    SKIPPED
}
