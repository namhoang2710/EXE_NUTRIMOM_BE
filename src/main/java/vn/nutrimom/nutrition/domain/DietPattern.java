package vn.nutrimom.nutrition.domain;

/**
 * Chế độ ăn người dùng tự chọn ở bước 3 onboarding.
 *
 * <p>Đây là <strong>điều kiện cứng</strong> cùng hạng với dị ứng: hết món thì trả {@code NO_MATCH}
 * kèm lý do, không bao giờ nới chế độ ăn ra để có kết quả (bất biến 5 của {@code _CONTRACT.md}).
 * Ưu tiên mềm được phép nới là thời gian nấu, ngân sách và nguyên liệu không thích.</p>
 *
 * <p>Null nghĩa là <em>chưa khai</em> — bước 3 bỏ qua được — chứ không phải {@link #OMNIVORE}.
 * Nhánh 4 gặp null thì bỏ hẳn điều kiện chế độ ăn, chứ không mặc định ăn tạp giúp người dùng.</p>
 */
public enum DietPattern {

    /** Ăn mọi nhóm thực phẩm. */
    OMNIVORE,

    /** Không ăn thịt động vật trên cạn, vẫn ăn cá và hải sản. */
    PESCATARIAN,

    /** Chay có trứng và sữa. */
    LACTO_OVO_VEGETARIAN,

    /** Chay trường, không dùng bất kỳ sản phẩm nào từ động vật. */
    VEGAN
}
