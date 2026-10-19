package vn.nutrimom.nutrition.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class SupportedDayCountValidator implements ConstraintValidator<SupportedDayCount, Integer> {

    /** Một ngày, ba ngày, một tuần. Danh sách đóng — xem {@link SupportedDayCount}. */
    public static final Set<Integer> SUPPORTED = Set.of(1, 3, 7);

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        // Bỏ trống = một ngày, mặc định của nhánh 5. Trường bắt buộc hay không là việc của
        // @NotNull, không phải của ràng buộc này.
        return value == null || SUPPORTED.contains(value);
    }
}
