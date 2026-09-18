package vn.nutrimom.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Chạy TRƯỚC chuỗi Spring Security (mặc định order -100) để requestId có sẵn trong MDC và header cho cả
// các lỗi phát ở tầng security filter (401/403/429), vì filter security chạy trước controller advice.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER_NAME);
        String requestId = isSafe(supplied) ? supplied : UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }

    private boolean isSafe(String value) {
        return value != null && value.length() <= 100 && value.matches("[A-Za-z0-9._:-]+");
    }
}
