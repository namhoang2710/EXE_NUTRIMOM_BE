package vn.nutrimom.auth.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import vn.nutrimom.auth.domain.OtpPurpose;

public class RegistrationTermsValidator
        implements ConstraintValidator<ValidRegistrationTerms, OtpChallengeRequest> {

    @Override
    public boolean isValid(OtpChallengeRequest request, ConstraintValidatorContext context) {
        if (request == null || request.purpose() != OtpPurpose.REGISTER
                || Boolean.TRUE.equals(request.acceptedTerms())) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                        context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("acceptedTerms")
                .addConstraintViolation();
        return false;
    }
}
