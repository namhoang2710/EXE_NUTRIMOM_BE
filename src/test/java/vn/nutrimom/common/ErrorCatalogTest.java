package vn.nutrimom.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import vn.nutrimom.common.exception.ErrorCode;

/**
 * Khoá hợp đồng error code (spec mục 20). Test này bảo vệ nguồn sự thật {@link ErrorCode}:
 * mọi code bắt buộc phải tồn tại và giữ đúng HTTP status đã công bố trong docs/error-codes.md.
 * Đổi ngầm status/tên code sẽ làm test đỏ.
 */
class ErrorCatalogTest {

    /** 22 code bắt buộc theo spec mục 20, kèm HTTP status kỳ vọng. */
    private static final Map<String, HttpStatus> MANDATORY = Map.ofEntries(
            Map.entry("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("UNAUTHORIZED", HttpStatus.UNAUTHORIZED),
            Map.entry("FORBIDDEN", HttpStatus.FORBIDDEN),
            Map.entry("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("VERSION_CONFLICT", HttpStatus.CONFLICT),
            Map.entry("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT),
            Map.entry("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("OTP_EXPIRED", HttpStatus.UNAUTHORIZED),
            Map.entry("INVALID_OTP", HttpStatus.UNAUTHORIZED),
            Map.entry("OTP_ATTEMPTS_EXCEEDED", HttpStatus.UNAUTHORIZED),
            Map.entry("OTP_CHALLENGE_USED", HttpStatus.UNAUTHORIZED),
            Map.entry("OTP_DEVICE_MISMATCH", HttpStatus.UNAUTHORIZED),
            Map.entry("INVALID_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED),
            Map.entry("FILE_TOO_LARGE", HttpStatus.CONTENT_TOO_LARGE),
            Map.entry("UNSUPPORTED_FILE_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
            Map.entry("MALWARE_DETECTED", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("UPLOAD_NOT_COMPLETE", HttpStatus.CONFLICT),
            Map.entry("SLOT_UNAVAILABLE", HttpStatus.CONFLICT),
            Map.entry("QUOTA_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("SCAN_FAILED", HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry("SHARING_SCOPE_REQUIRED", HttpStatus.FORBIDDEN),
            Map.entry("CONTENT_NOT_REVIEWED", HttpStatus.CONFLICT));

    @Test
    void everyMandatorySpecCodeExistsWithExpectedStatus() {
        MANDATORY.forEach((code, expectedStatus) -> {
            ErrorCode error = ErrorCode.valueOf(code);
            assertThat(error.status())
                    .as("HTTP status của %s", code)
                    .isEqualTo(expectedStatus);
        });
    }

    @Test
    void mandatoryCatalogCoversAllTwentyTwoSpecCodes() {
        assertThat(MANDATORY).hasSize(22);
    }

    @Test
    void wireCodeEqualsEnumName() {
        Arrays.stream(ErrorCode.values())
                .forEach(error -> assertThat(error.code()).isEqualTo(error.name()));
    }

    @Test
    void everyErrorCodeHasStatusAndMessage() {
        Arrays.stream(ErrorCode.values()).forEach(error -> {
            assertThat(error.status()).as("status của %s", error.name()).isNotNull();
            assertThat(error.defaultMessage()).as("defaultMessage của %s", error.name()).isNotBlank();
        });
    }
}
