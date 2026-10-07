package vn.nutrimom.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.AntPathMatcher;

/**
 * Mọi endpoint lời mời có thật sự rơi vào một policy hay không.
 *
 * <p>{@code RateLimitFilter} cho qua <strong>không giới hạn</strong> khi không rule nào khớp — một
 * route quên khai báo sẽ im lặng không bị chặn chứ không báo lỗi gì. Policy {@code default} trong
 * {@code application.yml} không cứu được: không route nào trỏ tới nó.</p>
 *
 * <p>Đọc cấu hình thật từ {@code application.yml}; profile test chỉ ghi đè {@code enabled}, còn
 * {@code routes} giữ nguyên. {@link RateLimitFilterTest} kiểm hành vi của filter với cấu hình tự
 * dựng, bài này kiểm chính cấu hình đang chạy.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RateLimitRouteCoverageTest {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Autowired RateLimitProperties properties;

    @Test
    void everyFamilyInvitationEndpointResolvesToAPolicy() {
        assertThat(policyFor("/api/v1/family-invitations")).isEqualTo("invitation-create");
        assertThat(policyFor("/api/v1/family-invitations/preview")).isEqualTo("invitation-token");
        assertThat(policyFor("/api/v1/family-invitations/accept")).isEqualTo("invitation-token");
        assertThat(policyFor("/api/v1/family-invitations/received")).isEqualTo("invitation-token");
        assertThat(policyFor("/api/v1/family-invitations/abc-123/preview"))
                .isEqualTo("invitation-token");
        assertThat(policyFor("/api/v1/family-invitations/abc-123/accept"))
                .isEqualTo("invitation-token");
    }

    /**
     * {@code /received} không được để một pattern một-segment nuốt mất.
     *
     * <p>Rule khớp đầu tiên thắng, nên nếu có ai thêm {@code /api/v1/family-invitations/*} lên trên
     * thì {@code /received} sẽ nhận policy của token-path thay vì của chính nó. Hôm nay hai thứ
     * trùng ngưỡng nên không thấy gì; bài này ghim lại trước khi chúng khác nhau.</p>
     */
    @Test
    void theInboxRouteIsMatchedByItsOwnRuleNotASingleSegmentWildcard() {
        assertThat(properties.getRoutes().stream()
                .filter(rule -> MATCHER.match(rule.pattern(), "/api/v1/family-invitations/received"))
                .findFirst().orElseThrow().pattern())
                .isEqualTo("/api/v1/family-invitations/received");
    }

    /** Mọi policy được route trỏ tới phải tồn tại — gõ sai tên là bỏ chặn, không phải lỗi khởi động. */
    @Test
    void everyRouteNamesAPolicyThatExists() {
        assertThat(properties.getRoutes())
                .allSatisfy(rule -> assertThat(properties.getPolicies())
                        .as("policy '%s' của route '%s'", rule.policy(), rule.pattern())
                        .containsKey(rule.policy()));
    }

    private String policyFor(String path) {
        return properties.getRoutes().stream()
                .filter(rule -> MATCHER.match(rule.pattern(), path))
                .map(RateLimitProperties.RouteRule::policy)
                .findFirst()
                .orElse(null);
    }
}
