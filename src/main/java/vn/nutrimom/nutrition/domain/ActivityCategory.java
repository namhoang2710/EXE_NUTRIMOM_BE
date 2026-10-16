package vn.nutrimom.nutrition.domain;

/**
 * Nhóm vận động dùng cho ước tính năng lượng EER.
 *
 * <p>Bốn nhóm này là <strong>biến của công thức EER</strong>, không phải nhãn mô tả lối sống: mỗi
 * nhóm có một bộ hệ số riêng trong {@code nutritionRules.json}.</p>
 *
 * <p><strong>Người dùng tự chọn.</strong> Không suy nhóm vận động từ số bước chân hay bất kỳ dữ
 * liệu cảm biến nào — đó là suy đoán, và EER sai kéo theo cả mục tiêu năng lượng sai.</p>
 */
public enum ActivityCategory {
    INACTIVE,
    LOW_ACTIVE,
    ACTIVE,
    VERY_ACTIVE
}
