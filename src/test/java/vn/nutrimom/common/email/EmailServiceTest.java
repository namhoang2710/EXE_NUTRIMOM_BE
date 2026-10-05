package vn.nutrimom.common.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Template email là text block cộng {@code String.formatted}, không phải engine có auto-escape.
 * Cả hai thứ đó hỏng lúc chạy chứ không lúc biên dịch — một dấu {@code %} quên nhân đôi trong CSS
 * là {@code IllegalFormatException} ngay trên production — nên phải có bài gọi dựng thật.
 */
class EmailServiceTest {

    private static final OffsetDateTime EXPIRES = OffsetDateTime.parse("2026-10-07T11:00:00Z");

    private final EmailService service = new EmailService(null, "no-reply@nutrimom.vn", "");

    @Test
    void renderingTheInvitationNeverThrows() {
        assertThatCode(() -> service.familyInvitationHtml("Mai", "Chồng/bạn đời",
                List.of("Xem lịch khám và nhắc nhở"),
                "http://localhost:5173/family/invite?token=abc", EXPIRES))
                .doesNotThrowAnyException();
    }

    /** Không có SMTP thì rơi về chế độ mock và trả false, tuyệt đối không ném ra ngoài. */
    @Test
    void sendingWithoutAnSmtpSenderReportsFailureInsteadOfThrowing() {
        assertThatCode(() -> assertThat(service.sendFamilyInvitation("an@example.com", "Mai",
                "Chồng/bạn đời", List.of("Xem lịch khám và nhắc nhở"),
                "http://localhost:5173/family/invite?token=abc", EXPIRES)).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    void renderingTheActivationEmailNeverThrows() {
        assertThatCode(() -> service.sendRegistrationConfirmation(
                "an@example.com", "Mai", "123456")).doesNotThrowAnyException();
    }

    @Test
    void userSuppliedNamesAreEscapedBeforeTheyReachTheHtml() {
        String html = service.familyInvitationHtml("<script>alert(1)</script>", "Chồng/bạn đời",
                List.of("<img onerror=x>"), "http://localhost:5173/family/invite?token=a\"b",
                EXPIRES);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).doesNotContain("<img onerror");
        assertThat(html).contains("&lt;script&gt;");
        // Dấu nháy kép trong URL phải bị escape, nếu không nó thoát khỏi thuộc tính href.
        assertThat(html).doesNotContain("token=a\"b");
    }

    @Test
    void theInvitationCarriesTheAcceptButtonAndAPlainLinkFallback() {
        String url = "https://nutrimom.vn/family/invite?token=xyz";

        String html = service.familyInvitationHtml("Mai", "Chồng/bạn đời",
                List.of("Xem lịch khám và nhắc nhở"), url, EXPIRES);

        assertThat(html).contains("Chấp nhận lời mời");
        assertThat(html).contains("href=\"" + url + "\"");
        // Outlook bỏ qua <button>, nên nút phải là thẻ a.
        assertThat(html).doesNotContain("<button");
        assertThat(html).contains("Xem lịch khám và nhắc nhở");
        // Người nhận phải được nhắc đăng nhập đúng tài khoản, vì accept đòi khớp email/sđt.
        assertThat(html).contains("đăng nhập");
        // Link gửi ra ngoài luôn là http(s); scheme riêng không mở được từ mail client.
        assertThat(html).doesNotContain("nutrimom://");
    }

    @Test
    void theExpiryIsRenderedInVietnamTimeNotUtc() {
        String html = service.familyInvitationHtml("Mai", "Chồng/bạn đời", List.of("Xem lịch"),
                "https://nutrimom.vn/family/invite?token=xyz", EXPIRES);

        // 11:00Z là 18:00 giờ Việt Nam — người đọc email không nên phải tự quy đổi.
        assertThat(html).contains("18:00 ngày 07/10/2026");
    }

    @Test
    void anEmptyScopeListStillRendersWithoutBlowingUp() {
        assertThatCode(() -> service.familyInvitationHtml("Mai", "Chồng/bạn đời", List.of(),
                "https://nutrimom.vn/family/invite?token=xyz", EXPIRES))
                .doesNotThrowAnyException();
    }
}
