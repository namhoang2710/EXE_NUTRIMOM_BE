package vn.nutrimom.nutrition.domain;

/**
 * Mức độ rà soát thành phần gây dị ứng của một nguyên liệu.
 *
 * <p>Hai giá trị này <strong>không được nhập làm một</strong>. {@link #KNOWN} kèm danh sách rỗng
 * nghĩa là "đã rà và không ghi nhận thành phần gây dị ứng" — nguyên liệu dùng được bình thường.
 * {@link #UNKNOWN} nghĩa là <em>chưa rà</em>, và món chứa nó bị loại khỏi thực đơn cá nhân hoá
 * (BR03) dù vẫn xem được trong phần tra cứu.</p>
 */
public enum AllergenStatus {
    KNOWN,
    UNKNOWN
}
