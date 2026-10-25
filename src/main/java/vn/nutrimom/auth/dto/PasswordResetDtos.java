package vn.nutrimom.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import vn.nutrimom.common.validation.PasswordPolicy;

/** Request/response của luồng quên mật khẩu và đổi mật khẩu. JSON là snake_case theo cấu hình chung. */
public final class PasswordResetDtos {
    private PasswordResetDtos() { }

    public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 255) String email) { }

    /** Luôn cùng một câu, dù email có tồn tại hay không: không cho dò email nào đã đăng ký. */
    public record ForgotPasswordResponse(boolean accepted, String message) { }

    public record ValidateResetTokenRequest(@NotBlank @Size(max = 128) String token) { }

    public record ValidateResetTokenResponse(boolean valid, OffsetDateTime expiresAt, String emailMasked) { }

    public record ResetPasswordRequest(
            @NotBlank @Size(max = 128) String token,
            @NotBlank
            @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                    message = PasswordPolicy.LENGTH_MESSAGE)
            @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE) String newPassword) { }

    public record ResetPasswordResponse(boolean reset, String message) { }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 100) String currentPassword,
            @NotBlank
            @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                    message = PasswordPolicy.LENGTH_MESSAGE)
            @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE) String newPassword) { }
}
