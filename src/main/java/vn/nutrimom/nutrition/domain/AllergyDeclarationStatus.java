package vn.nutrimom.nutrition.domain;

/**
 * Người dùng đã khai dị ứng tới đâu — <strong>ba</strong> trạng thái, không phải boolean (BR01).
 *
 * <p>Đừng nhầm với {@link AllergenStatus}: enum kia nói về <em>nguyên liệu trong danh mục</em> đã
 * được rà soát thành phần gây dị ứng hay chưa. Enum này nói về <em>người dùng</em> đã tự khai hay
 * chưa. Hai thứ độc lập; một nguyên liệu {@code KNOWN} vẫn có thể thuộc hồ sơ {@link #UNKNOWN}.</p>
 *
 * <p><strong>Vì sao không dùng boolean:</strong> boolean buộc "chưa hỏi" và "đã hỏi, không có"
 * phải chung một giá trị. Hệ thống khi đó tự kết luận giúp người dùng rằng họ không có dị ứng —
 * đúng điều BR01 cấm. Hệ quả cụ thể: hồ sơ {@link #UNKNOWN} vẫn xem được mọi nội dung và công
 * thức, nhưng danh sách gợi ý <strong>không được gắn nhãn</strong> "đã kiểm tra theo dị ứng của
 * bạn", và trước khi lưu thực đơn cá nhân hóa thì giao diện phải nhắc xác nhận lại.</p>
 *
 * <p>Đây là kiểm tra dữ liệu đầu vào, <strong>không có actor nào duyệt hồ sơ</strong>: không ai
 * xét duyệt lời khai của mẹ bầu, hệ thống chỉ ghi nhận mức độ chắc chắn của chính lời khai đó.</p>
 */
public enum AllergyDeclarationStatus {

    /** Đã hỏi, người dùng trả lời là không có dị ứng nào. */
    KNOWN_NONE,

    /** Đã hỏi, người dùng liệt kê ra danh sách — xem {@code app.user_allergens}. */
    KNOWN_LIST,

    /** Chưa hỏi, hoặc người dùng chọn "chưa rõ". Mặc định của hồ sơ mới. */
    UNKNOWN
}
