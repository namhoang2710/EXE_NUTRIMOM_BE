package vn.nutrimom.auth.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RegistrationTermsValidator.class)
public @interface ValidRegistrationTerms {
    String message() default "Bạn cần đồng ý Điều khoản sử dụng và Chính sách bảo mật.";

    Class<?>[] groups() default { };

    Class<? extends Payload>[] payload() default { };
}
