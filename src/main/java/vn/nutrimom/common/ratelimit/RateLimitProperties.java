package vn.nutrimom.common.ratelimit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình rate-limit dưới prefix {@code app.security.rate-limit}.
 *
 * <pre>
 * app:
 *   security:
 *     rate-limit:
 *       enabled: true
 *       policies:
 *         login: { capacity: 10, window: PT1M }
 *         otp:   { capacity: 5,  window: PT1M }
 *       routes:
 *         - { pattern: "/api/v1/auth/login", policy: login }
 *         - { pattern: "/api/v1/auth/otp/**", policy: otp }
 * </pre>
 *
 * Ngưỡng cụ thể là lựa chọn vận hành (spec mục 21 không quy định số); đổi qua config không cần build lại.
 */
@ConfigurationProperties(prefix = "app.security.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Map<String, RateLimitPolicy> policies = new LinkedHashMap<>();
    private List<RouteRule> routes = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, RateLimitPolicy> getPolicies() {
        return policies;
    }

    public void setPolicies(Map<String, RateLimitPolicy> policies) {
        this.policies = policies;
    }

    public List<RouteRule> getRoutes() {
        return routes;
    }

    public void setRoutes(List<RouteRule> routes) {
        this.routes = routes;
    }

    /** Ánh xạ một Ant path pattern sang tên policy trong {@link #policies}. */
    public record RouteRule(String pattern, String policy) {
    }
}
