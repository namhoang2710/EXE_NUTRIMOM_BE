package vn.nutrimom.nutrition.domain;

import java.math.BigDecimal;

/**
 * Năm tiêu chí của công thức điểm, kèm trọng số.
 *
 * <pre>
 * S = 0,35·N + 0,30·P + 0,15·V + 0,10·B + 0,10·C
 * </pre>
 *
 * <p>Mỗi thành phần nằm trong 0–100. Đây là <strong>cấu hình thử nghiệm sản phẩm, không phải công
 * thức y khoa</strong>: các con số nói món nào hợp hơn với hồ sơ, không nói món nào tốt cho sức
 * khỏe hơn.</p>
 *
 * <p>Tiêu chí nào không dùng được thì <strong>trọng số của nó bị loại khỏi phép tổng</strong> rồi
 * chuẩn hóa lại trên phần còn lại — không bao giờ cho 0 điểm. Cho 0 nghĩa là coi "chưa biết" bằng
 * "kém", và như thế mọi món thiếu dữ liệu sẽ bị đẩy xuống đáy chỉ vì danh mục còn trống.</p>
 */
public enum RankingCriterion {

    /** Dinh dưỡng — mức khớp với phần năng lượng và vi chất tham chiếu của bữa. */
    NUTRITION("N", "0.35"),

    /** Khẩu vị — sở thích người dùng đã khai và phản hồi sau bữa ăn. */
    PREFERENCE("P", "0.30"),

    /** Đa dạng — tránh lặp món và lặp nguyên liệu chính. */
    VARIETY("V", "0.15"),

    /** Chi phí so với ngân sách bữa. */
    BUDGET("B", "0.10"),

    /** Thuận tiện — thời gian nấu so với giới hạn người dùng đặt. */
    CONVENIENCE("C", "0.10");

    private final String symbol;
    private final BigDecimal weight;

    RankingCriterion(String symbol, String weight) {
        this.symbol = symbol;
        this.weight = new BigDecimal(weight);
    }

    /** Ký hiệu trong công thức — dùng khi ghi {@code criteria_used} vào nhật ký gợi ý. */
    public String symbol() {
        return symbol;
    }

    public BigDecimal weight() {
        return weight;
    }
}
