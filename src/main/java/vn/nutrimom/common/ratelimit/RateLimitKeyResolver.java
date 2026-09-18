package vn.nutrimom.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Dựng khóa đếm rate-limit cho một request.
 *
 * <p>Request đã xác thực → khóa theo userId (claim {@code sub}) để giới hạn theo người dùng. Request chưa
 * xác thực (login/OTP) → khóa theo IP (kèm X-Device-Id nếu có) vì chưa biết là ai.</p>
 */
@Component
public class RateLimitKeyResolver {

    private static final String DEVICE_HEADER = "X-Device-Id";

    public String resolve(String policyName, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
            return policyName + ":u:" + jwt.getSubject();
        }
        String ip = request.getRemoteAddr();
        String device = request.getHeader(DEVICE_HEADER);
        String suffix = device == null || device.isBlank() ? "" : ":d:" + device.trim();
        return policyName + ":ip:" + ip + suffix;
    }
}
