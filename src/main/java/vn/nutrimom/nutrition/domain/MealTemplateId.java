package vn.nutrimom.nutrition.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Khoá ghép của {@link MealTemplateEntity}.
 *
 * <p>Một loại bữa có thể có nhiều hơn một cấu trúc hợp lệ — bữa sáng vừa chấp nhận một món hoàn
 * chỉnh, vừa chấp nhận món chính kèm phần ăn kèm — nên khoá là cặp (loại bữa, chế độ) chứ không
 * phải riêng loại bữa.</p>
 */
public class MealTemplateId implements Serializable {

    private MealType mealType;
    private TemplateMode mode;

    public MealTemplateId() {
    }

    public MealTemplateId(MealType mealType, TemplateMode mode) {
        this.mealType = mealType;
        this.mode = mode;
    }

    public MealType getMealType() { return mealType; }
    public void setMealType(MealType value) { mealType = value; }

    public TemplateMode getMode() { return mode; }
    public void setMode(TemplateMode value) { mode = value; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MealTemplateId that)) {
            return false;
        }
        return mealType == that.mealType && mode == that.mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mealType, mode);
    }
}
