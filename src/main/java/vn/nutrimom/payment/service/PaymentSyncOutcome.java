package vn.nutrimom.payment.service;

/**
 * Kết luận rút ra từ trạng thái PayOS của một link thanh toán.
 *
 * <p>{@link #UNCHANGED} là một kết luận tường minh, không phải chỗ trống: "cổng đã trả lời và câu
 * trả lời là chưa ngã ngũ" khác hẳn "không đọc được cổng". Dùng {@code Optional.empty()} cho ca này
 * thì hai thứ đó trùng nhau, và người đọc sau sẽ tưởng việc không ghi gì là một lỗ hổng cần vá.</p>
 */
public enum PaymentSyncOutcome {
    PAID,
    CANCELLED,
    EXPIRED,
    UNCHANGED
}
