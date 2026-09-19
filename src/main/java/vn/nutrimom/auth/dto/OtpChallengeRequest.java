package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.*;
import vn.nutrimom.auth.domain.OtpPurpose;

@ValidRegistrationTerms
public record OtpChallengeRequest(
        @NotBlank(message = "Số điện thoại không được để trống")
        @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự") String phone,
        @NotNull(message = "Mục đích OTP không được để trống") OtpPurpose purpose,
        Boolean acceptedTerms,
        @Size(max = 100, message = "Mã thiết bị tối đa 100 ký tự") String deviceId
) { }
