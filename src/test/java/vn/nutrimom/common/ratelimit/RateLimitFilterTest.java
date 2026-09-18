package vn.nutrimom.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.nutrimom.security.RateLimitFilter;

/**
 * Kiểm đơn vị {@link RateLimitFilter}: trong ngưỡng thì cho qua; vượt ngưỡng thì trả 429 đúng envelope
 * spec + header Retry-After và KHÔNG gọi tiếp filter chain. Tự dựng cấu hình bật rate-limit nên không phụ
 * thuộc profile test (vốn tắt rate-limit).
 */
class RateLimitFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsWithinCapacityThenBlocksWithRateLimitedEnvelope() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setPolicies(Map.of("login", new RateLimitPolicy(2, Duration.ofMinutes(1))));
        properties.setRoutes(List.of(new RateLimitProperties.RouteRule("/api/v1/auth/login", "login")));

        RateLimitFilter filter = new RateLimitFilter(
                properties, new InMemoryRateLimiterStore(), new RateLimitKeyResolver());

        // Hai lượt đầu (capacity=2) phải được cho qua.
        assertThat(invoke(filter).chainCalled).isTrue();
        assertThat(invoke(filter).chainCalled).isTrue();

        // Lượt thứ ba vượt ngưỡng → 429.
        Outcome blocked = invoke(filter);
        assertThat(blocked.chainCalled).isFalse();
        assertThat(blocked.response.getStatus()).isEqualTo(429);
        assertThat(blocked.response.getHeader("Retry-After")).isNotNull();
        assertThat(blocked.response.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    void ignoresRoutesWithoutPolicy() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setPolicies(Map.of("login", new RateLimitPolicy(1, Duration.ofMinutes(1))));
        properties.setRoutes(List.of(new RateLimitProperties.RouteRule("/api/v1/auth/login", "login")));
        RateLimitFilter filter = new RateLimitFilter(
                properties, new InMemoryRateLimiterStore(), new RateLimitKeyResolver());

        // Đường dẫn không khớp route → không giới hạn dù gọi nhiều lần.
        for (int i = 0; i < 5; i++) {
            Outcome outcome = invoke(filter, "/api/v1/pregnancies");
            assertThat(outcome.chainCalled).isTrue();
        }
    }

    private Outcome invoke(RateLimitFilter filter) throws Exception {
        return invoke(filter, "/api/v1/auth/login");
    }

    private Outcome invoke(RateLimitFilter filter, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain.getRequest() != null);
    }

    private record Outcome(MockHttpServletResponse response, boolean chainCalled) {
    }
}
