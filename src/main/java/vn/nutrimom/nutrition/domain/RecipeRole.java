package vn.nutrimom.nutrition.domain;

/**
 * Vai trò của món trong một bữa.
 *
 * <p>Dùng để lọc ứng viên <strong>trước khi</strong> chấm điểm (nhánh 4): món {@link #SIDE} không
 * được tranh thứ hạng với {@link #MAIN_DISH} khi ghép bữa, nếu không bữa trưa sẽ gồm ba món ăn kèm
 * mà thiếu món chính.</p>
 */
public enum RecipeRole {
    MAIN_DISH,
    STAPLE,
    PROTEIN,
    VEGETABLE,
    SIDE,
    SNACK
}
