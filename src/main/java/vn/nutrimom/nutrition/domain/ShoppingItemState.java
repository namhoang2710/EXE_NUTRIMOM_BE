package vn.nutrimom.nutrition.domain;

/**
 * Trạng thái một dòng đi chợ.
 *
 * <p>Ba giá trị trả lời ba câu khác nhau, và <strong>không</strong> thay thế được cho lượng còn
 * cần: một dòng {@link #PURCHASED} vẫn có thể còn thiếu sau khi người dùng đổi món làm tăng lượng
 * (TC22). Trạng thái nói <em>người dùng đã làm gì</em>; lượng còn cần là một phép trừ.</p>
 */
public enum ShoppingItemState {

    /** Chưa đánh dấu gì — mặc định của mọi dòng mới. */
    NEEDED,

    /** Đã có sẵn ở nhà, không phải mua (hoặc chỉ phải mua phần thiếu). */
    HAVE,

    /** Đã mua trong lượt đi chợ này. */
    PURCHASED
}
