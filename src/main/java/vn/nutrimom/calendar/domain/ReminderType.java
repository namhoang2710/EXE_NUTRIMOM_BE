package vn.nutrimom.calendar.domain;

import java.time.Period;

/**
 * Loại nhắc nhở, kèm khoảng cách tới mốc kế tiếp để gợi ý khi người dùng đánh dấu
 * {@link ReminderStatus#DONE}.
 *
 * <p>Đây là chỗ thay thế tạm cho {@code recurrence_rule} của spec mục 10: thay vì sinh chuỗi mốc
 * lặp (kéo theo scope {@code THIS|THIS_AND_FUTURE|ALL} khi sửa/xoá), v1 chỉ trả về một gợi ý
 * <em>không lưu</em> và để người dùng quyết định có tạo mốc mới hay không.</p>
 *
 * <p>Khoảng cách là hằng số phẳng, cố ý không phụ thuộc tuổi thai: tần suất khám thực tế do bác sĩ
 * chỉ định, backend không được đoán thay. Người dùng luôn sửa được ngày trước khi tạo.</p>
 */
public enum ReminderType {
    /** Tái khám theo hẹn của bác sĩ. */
    FOLLOW_UP(Period.ofDays(7)),
    /** Khám định kỳ. */
    ROUTINE_CHECKUP(Period.ofDays(28)),
    /** Mốc tự do; không gợi ý mốc kế tiếp vì không có chu kỳ nào để suy ra. */
    CUSTOM(null);

    private final Period nextSuggestionInterval;

    ReminderType(Period nextSuggestionInterval) {
        this.nextSuggestionInterval = nextSuggestionInterval;
    }

    /** Khoảng cách tới mốc gợi ý kế tiếp, hoặc {@code null} nếu loại này không gợi ý. */
    public Period nextSuggestionInterval() {
        return nextSuggestionInterval;
    }
}
