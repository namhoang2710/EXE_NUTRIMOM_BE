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
@org.springframework.test.context.bean.override.mockito.MockitoBean(types = {vn.nutrimom.assistant.service.GroqAssistantClient.class, vn.nutrimom.consultation.video.LiveKitClient.class})
@ActiveProfiles("test")
class RateLimitRouteCoverageTest {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Autowired RateLimitProperties properties;

    @Test void adminStreamReconnectsHaveTheirOwnQuota() {
        assertThat(policyFor("/api/v1/admin/realtime/stream")).isEqualTo("admin-stream");
        assertThat(properties.getPolicies().get("admin-stream").capacity()).isEqualTo(20);
    }
    @Test void expertEntryActionsUseTheNormalAuthenticatedQuota() {
        assertThat(policyFor("/api/v1/consultation-requests/booking/video/check-in")).isEqualTo("default");
        assertThat(policyFor("/api/v1/consultation-requests/booking/video/join")).isEqualTo("default");
    }

    @Test
    void adminExportsUseSpecificQuotaBeforeTheDefaultAdminRule() {
        assertThat(policyFor("/api/v1/admin/reports/REVENUE/export")).isEqualTo("admin-export");
        assertThat(policyFor("/api/v1/admin/dashboard")).isEqualTo("default");
        assertThat(properties.getPolicies().get("admin-export").capacity()).isEqualTo(5);
    }

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
    /** Route auth ẩn danh mà rơi khỏi rate limit thì mã 6 chữ số và hộp thư người khác bị dò/spam vô hạn. */
    @Test
    void everyAnonymousAuthRouteHasItsOwnQuota() {
        assertThat(policyFor("/api/v1/auth/password/forgot")).isEqualTo("email-send");
        assertThat(policyFor("/api/v1/auth/password/reset/validate")).isEqualTo("code-verify");
        assertThat(policyFor("/api/v1/auth/password/reset")).isEqualTo("code-verify");
        assertThat(policyFor("/api/v1/auth/password/change")).isEqualTo("password-change");
        assertThat(policyFor("/api/v1/auth/register")).isEqualTo("register");
        assertThat(policyFor("/api/v1/auth/activate")).isEqualTo("code-verify");
        assertThat(policyFor("/api/v1/auth/resend-activation")).isEqualTo("email-send");
        assertThat(policyFor("/api/v1/auth/magic-link/request")).isEqualTo("email-send");
        assertThat(policyFor("/api/v1/auth/magic-link/verify")).isEqualTo("code-verify");
        assertThat(policyFor("/api/v1/auth/login")).isEqualTo("login");
        assertThat(policyFor("/api/v1/auth/otp/verify")).isEqualTo("otp");
    }

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
