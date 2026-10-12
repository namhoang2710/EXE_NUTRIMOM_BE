package vn.nutrimom.entitlement.domain;

/**
 * Quyền lợi bán theo gói (spec mục 17).
 *
 * <p>Danh sách và <strong>thứ tự</strong> khớp đúng bảng quyền lợi trên trang giá của frontend
 * (<code>pricing-catalog.ts</code>). Bảng so sánh của FE dựng theo vị trí: nó lấy nhãn từ gói đầu
 * tiên rồi index mọi gói còn lại bằng cùng chỉ số, nên thêm/bớt/đảo một dòng ở đây là dán sai nhãn
 * cho cả cột khác. Mọi gói phải khai đủ 10 feature này, có hoặc không.</p>
 */
public enum PlanFeature {

    FAMILY_SHARING("Liên kết gia đình"),
    CONCEPTION_CALCULATOR("Tính ngày thụ thai"),
    MEDICAL_RECORDS("Lưu hồ sơ sức khỏe"),
    AI_SCAN("AI Scan Vision"),
    CALENDAR_REMINDERS("Lịch nhắc nhở"),
    PERSONALISED_GUIDANCE("Gợi ý cá nhân toàn diện"),
    CHATBOT("Chatbot đồng hành"),
    CONSULTATION("Tư vấn"),
    DOCTOR_BOOKING("Đặt lịch bác sĩ"),
    SUPPORT("Luôn nhận hỗ trợ");

    private final String label;

    PlanFeature(String label) {
        this.label = label;
    }

    /** Nhãn tiếng Việt hiển thị trên bảng giá. */
    public String label() {
        return label;
    }
}
