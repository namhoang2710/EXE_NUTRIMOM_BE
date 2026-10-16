package vn.nutrimom.nutrition.domain;

/**
 * Nhóm tam cá nguyệt theo cách công thức EER chia, <strong>không phải</strong> cách hiển thị giai
 * đoạn thai kỳ cho người dùng.
 *
 * <p>Mô hình EER chỉ có hai bộ hệ số: một cho tam cá nguyệt 1, một dùng chung cho tam cá nguyệt 2
 * và 3. Ranh giới nằm ở mốc tuần thai khai trong {@code nutritionRules.json}
 * ({@code trimester2StartWeek}, mặc định 14 tuần tròn): 13 tuần 6 ngày vẫn là {@link #FIRST},
 * 14 tuần 0 ngày đã là {@link #SECOND_THIRD} (TC04).</p>
 */
public enum TrimesterGroup {
    FIRST,
    SECOND_THIRD
}
