package vn.nutrimom.entitlement.domain;

/**
 * Chu kỳ của một hạn mức.
 *
 * <p>Ba gói không chỉ khác nhau con số mà khác cả chu kỳ ("2 lượt trải nghiệm tổng cộng" / "2 lượt
 * mỗi ngày" / "không giới hạn"), nên một số nguyên không đủ diễn đạt. {@link #UNLIMITED} được biểu
 * diễn bằng chính chu kỳ thay vì một ma số kiểu <code>-1</code>.</p>
 */
public enum QuotaPeriod {

    /** Hết là hết, không hồi lại. */
    LIFETIME,

    /** Reset mỗi ngày. */
    DAILY,

    /** Không giới hạn — phần <code>limit</code> đi kèm không mang nghĩa gì. */
    UNLIMITED
}
