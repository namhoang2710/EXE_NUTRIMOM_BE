package vn.nutrimom.nutrition.domain;

/**
 * Kết luận của bước lọc điều kiện cho một món, với một hồ sơ cụ thể.
 *
 * <p>Chỉ {@link #ELIGIBLE} mới đi tiếp sang bước chấm điểm (BR04) và mới vào được thực đơn cá
 * nhân hóa. Món chưa qua lọc <strong>không bao giờ</strong> được chấm điểm — hai bước nối tiếp,
 * không song song (bất biến 2).</p>
 *
 * <p>{@link #BLOCKED} và {@link #UNKNOWN} <strong>không được gộp làm một</strong>. Gộp lại thì mất
 * đúng nửa phần giải thích: không nói được với người dùng rằng món vắng mặt vì <em>dữ liệu còn
 * thiếu</em> chứ không phải vì nó không hợp với họ — và cũng mất luôn tín hiệu cho nhóm nhập liệu
 * biết nguyên liệu nào cần rà soát.</p>
 */
public enum EligibilityStatus {

    /** Đã kiểm đủ và không vi phạm điều kiện bắt buộc nào. */
    ELIGIBLE,

    /** Đã biết rõ và vi phạm: dị ứng, chế độ ăn, nguyên liệu phải tránh, giới hạn cứng. */
    BLOCKED,

    /**
     * Chưa đủ dữ liệu để kết luận — có nguyên liệu <em>chưa được rà soát</em> thành phần gây dị ứng.
     *
     * <p>BR03: món này không vào thực đơn cá nhân hóa, nhưng <strong>vẫn nằm trong phần tra
     * cứu</strong> ở {@code GET /api/v1/recipes}. Không rõ không có nghĩa là có hại.</p>
     */
    UNKNOWN
}
