package vn.nutrimom.family.domain;

/** Kết quả gửi lời mời ra ngoài, để giao diện biết có cần mời chủ nhóm copy link gửi tay không. */
public enum InvitationDeliveryStatus {
    /** Đã gửi email tới địa chỉ được mời. */
    SENT,
    /** Có địa chỉ email nhưng gửi hỏng (SMTP chưa cấu hình, hoặc lỗi mạng). */
    FAILED,
    /**
     * Không có kênh nào để gửi — mời bằng số điện thoại.
     *
     * <p>Hệ thống chưa tích hợp nhà cung cấp SMS nào, nên trường hợp này chủ nhóm phải tự gửi
     * {@code invite_url}. Trả về trạng thái thật thay vì im lặng là có chủ đích: một lời mời tưởng
     * đã gửi mà thực ra không đi đâu cả thì tệ hơn là nói thẳng.</p>
     */
    SKIPPED
}
