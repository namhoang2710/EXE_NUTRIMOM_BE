package vn.nutrimom.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.ratelimit.RateLimitKeyResolver;
import vn.nutrimom.common.ratelimit.RateLimitPolicy;
import vn.nutrimom.common.ratelimit.RateLimitProperties;
import vn.nutrimom.common.ratelimit.RateLimiterStore;
import vn.nutrimom.common.ratelimit.RateLimiterStore.ConsumeResult;

/**
 * Giới hạn tần suất ở tầng filter. Đặt trong package {@code vn.nutrimom.security} để tái dùng
 * {@link SecurityErrorWriter} (chạy trước {@code GlobalExceptionHandler}) khi phát 429.
 *
 * <p>Được khởi tạo và gắn vào chuỗi bởi {@code SecurityConfig} (không đánh dấu là bean/@Component để
 * tránh servlet container tự đăng ký thành filter thứ hai). Chạy sau khi xác thực xong nên request đã
 * đăng nhập được khóa theo user, còn login/OTP khóa theo IP.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final RateLimiterStore store;
    private final RateLimitKeyResolver keyResolver;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RateLimitFilter(RateLimitProperties properties, RateLimiterStore store,
                           RateLimitKeyResolver keyResolver) {
        this.properties = properties;
        this.store = store;
        this.keyResolver = keyResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        String policyName = matchPolicy(request);
        RateLimitPolicy policy = policyName == null ? null : properties.getPolicies().get(policyName);
        if (policy == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = keyResolver.resolve(policyName, request);
        ConsumeResult result = store.tryConsume(key, policy);
        if (result.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setHeader("Retry-After", Long.toString(result.retryAfterSeconds()));
        SecurityErrorWriter.write(response, ErrorCode.RATE_LIMITED,
                ErrorCode.RATE_LIMITED.defaultMessage(), MDC.get("requestId"));
    }

    private String matchPolicy(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (RateLimitProperties.RouteRule rule : properties.getRoutes()) {
            if (pathMatcher.match(rule.pattern(), path)) {
                return rule.policy();
            }
        }
        return null;
    }
}
