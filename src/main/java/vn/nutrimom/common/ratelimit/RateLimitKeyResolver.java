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
 * xác thực (login/OTP/quên mật khẩu) → khóa theo IP vì chưa biết là ai.</p>
 *
 * <p>KHÔNG ghép thêm {@code X-Device-Id} vào khóa ẩn danh: header đó do client tự đặt, nên trước đây chỉ
 * cần đổi giá trị mỗi request là có quota mới, và giới hạn của login/OTP/mã kích hoạt mất tác dụng.
 * Đổi lại, nhiều người chung một IP (wifi trường, NAT nhà mạng) chia chung quota của các route ẩn danh —
 * ngưỡng trong {@code application.yml} đã tính tới việc đó. IP thật lấy qua
 * {@code server.forward-headers-strategy}.</p>
 */
@Component
public class RateLimitKeyResolver {

    public String resolve(String policyName, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
            return policyName + ":u:" + jwt.getSubject();
        }
        return policyName + ":ip:" + request.getRemoteAddr();
    }
}
