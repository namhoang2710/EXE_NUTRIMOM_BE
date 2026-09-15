package vn.nutrimom.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import vn.nutrimom.common.exception.ErrorCode;

/**
 * Ghi lỗi ở lớp security filter (chạy TRƯỚC {@code GlobalExceptionHandler}, nên không đi qua
 * @RestControllerAdvice được). Chủ động dựng đúng error envelope của spec 1.2, lấy code + HTTP
 * status từ {@link ErrorCode} để đồng nhất với phần còn lại của hệ thống.
 */
final class SecurityErrorWriter {
    private SecurityErrorWriter() {
    }

    static void write(HttpServletResponse response, ErrorCode error,
                      String message, String requestId) throws IOException {
        response.setStatus(error.status().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        String safeRequestId = requestId == null ? "" : escape(requestId);
        String json = "{\"error\":{\"code\":\"" + escape(error.code())
                + "\",\"message\":\"" + escape(message)
                + "\",\"fields\":{},\"retryable\":" + error.retryable()
                + ",\"request_id\":\"" + safeRequestId + "\"}}";
        response.getWriter().write(json);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
