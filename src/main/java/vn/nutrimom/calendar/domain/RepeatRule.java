package vn.nutrimom.calendar.domain;

/**
 * Kiểu lặp của một nhắc nhở. {@code null} ở entity nghĩa là mốc một lần.
 *
 * <p>Cố ý không dùng chuỗi RRULE của iCalendar: ba kiểu dưới đây phủ hết nhu cầu thực tế của app
 * (vitamin hằng ngày, thuốc cách ngày, cân nặng theo thứ, khám hằng tháng), lại kiểm tra được bằng
 * CHECK constraint ở tầng DB — chuỗi RRULE thì không.</p>
 */
public enum RepeatRule {
    /** Mỗi N ngày. {@code interval = 2} chính là "uống cách ngày". */
    DAILY,
    /** Mỗi N tuần, vào các thứ đã chọn — giống phần "Repeat" của báo thức điện thoại. */
    WEEKLY,
    /** Mỗi N tháng, vào cùng ngày trong tháng. Tháng ngắn hơn thì lùi về ngày cuối tháng. */
    MONTHLY
}
