package vn.nutrimom.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class SecurityErrorWriter {
    private SecurityErrorWriter() {
    }

    static void write(HttpServletResponse response, int status, String code,
                      String message, String requestId) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        String safeRequestId = requestId == null ? "" : escape(requestId);
        String json = "{\"error\":{\"code\":\"" + escape(code)
                + "\",\"message\":\"" + escape(message)
                + "\",\"fields\":{},\"retryable\":false,\"request_id\":\""
                + safeRequestId + "\"}}";
        response.getWriter().write(json);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
