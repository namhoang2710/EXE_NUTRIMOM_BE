package vn.nutrimom.nutrition.domain;

/**
 * Kết quả của một lượt <strong>sinh</strong> thực đơn (cột {@code generation_status}).
 *
 * <p>Ba giá trị này nói về <em>việc tìm kiếm</em>, không nói về việc lưu: trạng thái lưu nằm ở
 * {@link PlanLifecycleState}, và độ đầy đủ dữ liệu nằm ở {@link CoverageStatus}. Ba nhóm trạng thái
 * <strong>không được dùng thay cho nhau</strong> — một thực đơn {@link #READY} vẫn có thể mang
 * {@link CoverageStatus#PARTIAL}, và một thực đơn {@link #READY_WITH_NOTES} vẫn lưu được bình
 * thường.</p>
 *
 * <p>{@link #READY} <strong>không phải chứng nhận y tế</strong>. Nó chỉ nói bộ sinh đã hoàn thành
 * mọi vị trí trong khuôn các điều kiện bắt buộc của hồ sơ.</p>
 */
public enum GenerationStatus {

    /** Hoàn thành mọi vị trí và không còn điểm nào cần người dùng xem lại. */
    READY,

    /**
     * Đạt mọi điều kiện cứng nhưng còn ưu tiên mềm chưa trọn, hoặc còn dữ liệu thiếu.
     *
     * <p>Giao diện phải <strong>chỉ rõ</strong> điểm nào: chi phí, thời gian, hay dữ liệu dinh
     * dưỡng. "Có vài điểm cần xem" mà không nói là điểm gì thì người dùng không làm gì được với nó.
     * </p>
     */
    READY_WITH_NOTES,

    /**
     * Không hoàn thành được vị trí theo điều kiện cứng.
     *
     * <p><strong>Không phải mã lỗi</strong> — trả trong thân HTTP 200 kèm lý do, chỉ ra
     * <em>bữa nào</em> và <em>bộ lọc nào</em> gây giới hạn. Không bao giờ nới điều kiện dị ứng hay
     * chế độ ăn bắt buộc để có kết quả (bất biến 5, BR14); lời mời nới chỉ áp cho ưu tiên mềm:
     * thời gian, ngân sách, món không thích.</p>
     *
     * <p>Giá trị này <strong>chỉ xuất hiện ở bản xem trước</strong>. Bảng {@code app.meal_plans}
     * không nhận nó — xem ràng buộc {@code ck_meal_plans_generation_status} ở V60.</p>
     */
    NO_MATCH
}
