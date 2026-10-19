package vn.nutrimom.nutrition.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code day_count} chỉ nhận <strong>1, 3 hoặc 7</strong> (nhánh 9).
 *
 * <p>Không dùng {@code @Min}/{@code @Max} vì tập giá trị không liền nhau: 5 nằm giữa 3 và 7 nhưng
 * không phải lựa chọn hợp lệ — MVP chỉ làm một ngày, ba ngày và một tuần. Không dùng
 * {@code @AssertTrue} trên một phương thức dẫn xuất vì lỗi khi đó gắn vào tên phương thức, còn
 * giao diện cần lỗi nằm đúng ở trường {@code day_count}.</p>
 */
@Documented
@Constraint(validatedBy = SupportedDayCountValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface SupportedDayCount {

    String message() default "day_count chỉ nhận 1, 3 hoặc 7";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
