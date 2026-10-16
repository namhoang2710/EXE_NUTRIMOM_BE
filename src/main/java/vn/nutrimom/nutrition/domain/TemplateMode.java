package vn.nutrimom.nutrition.domain;

/**
 * Cách dựng một bữa: một món hoàn chỉnh, hoặc một tập vai trò.
 *
 * <p>Không có chế độ thứ ba "trộn ngẫu nhiên" — mỗi bữa phải theo đúng một trong hai cấu trúc
 * này (BR06).</p>
 */
public enum TemplateMode {
    SINGLE_DISH,
    ROLE_SET
}
