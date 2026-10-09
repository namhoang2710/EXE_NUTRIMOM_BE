package vn.nutrimom.family.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.email.EmailService;
import vn.nutrimom.common.email.EmailService.MailResult;
import vn.nutrimom.config.FamilyInvitationProperties;
import vn.nutrimom.config.FrontendProperties;
import vn.nutrimom.family.domain.FamilyInvitationEntity;
import vn.nutrimom.family.domain.FamilyRelationship;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.domain.InvitationDeliveryStatus;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.service.NotificationService;

/**
 * Hai kênh gửi độc lập nhau, và cả hai đều không được phép làm hỏng lời mời đã nằm trong DB.
 *
 * <p>Mỗi bài chỉ gọi <em>một</em> kênh. Hàm {@code deliver} gộp cả hai đã bị xoá vì email phải rời
 * tiến trình sau commit còn thông báo in-app thì ở lại trong transaction — và vì
 * {@code MockitoExtension} chạy {@code STRICT_STUBS}, một bài stub cả hai collaborator trong khi
 * chỉ chạy một chân sẽ đỏ với {@code UnnecessaryStubbingException}.</p>
 */
@ExtendWith(MockitoExtension.class)
class FamilyInvitationNotifierTest {

    @Mock private EmailService email;
    @Mock private NotificationService notifications;
    @Mock private UserRepository users;

    private FamilyInvitationProperties properties;
    private FamilyInvitationNotifier notifier;

    @BeforeEach
    void setUp() {
        properties = new FamilyInvitationProperties();
        FrontendProperties frontend = new FrontendProperties();
        notifier = new FamilyInvitationNotifier(email, notifications, users, frontend, properties);
    }

    @Test
    void invitingByEmailSendsMailAndReportsSent() {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenReturn(MailResult.ok());

        var outcome = notifier.sendEmail(invitation("an@example.com", null), "tok", "Mai");

        assertThat(outcome.status()).isEqualTo(InvitationDeliveryStatus.SENT);
        assertThat(outcome.sentAt()).isNotNull();
        verify(email).sendFamilyInvitation(eq("an@example.com"), eq("Mai"), eq("Chồng/bạn đời"),
                anyList(), anyString(), any());
    }

    /**
     * Mời bằng số điện thoại không còn kênh ngoài nào sau khi bỏ SMS. Nói thẳng SKIPPED để
     * giao diện mời chủ nhóm copy link, thay vì im lặng để họ tưởng tin nhắn đã đi.
     */
    @Test
    void invitingByPhoneSkipsEmailEntirely() {
        var outcome = notifier.sendEmail(invitation(null, "0912345678"), "tok", "Mai");

        assertThat(outcome.status()).isEqualTo(InvitationDeliveryStatus.SKIPPED);
        assertThat(outcome.sentAt()).isNull();
        verifyNoInteractions(email);
    }

    /** Cờ tắt được kiểm trước khi chạm {@link EmailService}, nên ra SKIPPED chứ không FAILED. */
    @Test
    void disablingEmailSkipsTheSendWithoutTouchingSmtp() {
        properties.setEmailEnabled(false);

        var outcome = notifier.sendEmail(invitation("an@example.com", null), "tok", "Mai");

        assertThat(outcome.status()).isEqualTo(InvitationDeliveryStatus.SKIPPED);
        assertThat(outcome.sentAt()).isNull();
        verifyNoInteractions(email);
    }

    @Test
    void aFailedSendIsReportedRatherThanPropagated() {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any())).thenThrow(new IllegalStateException("smtp down"));

        var outcome = notifier.sendEmail(invitation("an@example.com", null), "tok", "Mai");

        assertThat(outcome.status()).isEqualTo(InvitationDeliveryStatus.FAILED);
        assertThat(outcome.sentAt()).isNull();
        assertThat(outcome.errorClass()).isEqualTo("IllegalStateException");
    }

    /**
     * {@code FAILED} phải kèm lý do, không được ra {@code error=none}.
     *
     * <p>Bản cũ chỉ nhận một {@code boolean} từ {@link EmailService} nên log ghi
     * {@code delivery_status=FAILED error=none} — QA đọc log không biết là SMTP sai mật khẩu,
     * sai host, hay chỉ đơn giản chưa cấu hình. Đây cũng là nhánh {@code false} mà trước đây
     * <strong>không bài nào</strong> phủ: ca FAILED duy nhất đi qua {@code thenThrow}.</p>
     */
    @Test
    void aRefusedSendCarriesTheReasonNotJustAFalse() {
        when(email.sendFamilyInvitation(anyString(), anyString(), anyString(), anyList(),
                anyString(), any()))
                .thenReturn(MailResult.failed(MailResult.NOT_CONFIGURED));

        var outcome = notifier.sendEmail(invitation("an@example.com", null), "tok", "Mai");

        assertThat(outcome.status()).isEqualTo(InvitationDeliveryStatus.FAILED);
        assertThat(outcome.sentAt()).isNull();
        assertThat(outcome.errorClass()).isEqualTo(MailResult.NOT_CONFIGURED);
    }

    @Test
    void anExistingAccountGetsAnInAppNotification() {
        UserEntity invitee = new UserEntity();
        invitee.setId("user-1");
        when(users.findByEmailIgnoreCase("an@example.com")).thenReturn(Optional.of(invitee));

        notifier.notifyInApp(invitation("an@example.com", null), "Mai");

        verify(notifications).publish(eq("user-1"), eq(NotificationType.FAMILY),
                eq("Lời mời tham gia nhóm gia đình"), anyString(), anyString(),
                eq("FAMILY_INVITATION"), any());
    }

    /** Không tra được tài khoản thì im lặng: khác biệt ở đây là oracle liệt kê tài khoản. */
    @Test
    void anUnknownTargetProducesNoNotificationAndNoError() {
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatCode(() -> notifier.notifyInApp(
                invitation("khongco@example.com", null), "Mai"))
                .doesNotThrowAnyException();

        verifyNoInteractions(notifications);
    }

    @Test
    void aFailingNotificationDoesNotBreakTheInvitation() {
        UserEntity invitee = new UserEntity();
        invitee.setId("user-1");
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(invitee));
        when(notifications.publish(anyString(), any(), anyString(), anyString(), anyString(),
                anyString(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> notifier.notifyInApp(invitation("an@example.com", null), "Mai"))
                .doesNotThrowAnyException();
    }

    /**
     * Cờ tắt gửi chỉ đóng kênh email, kênh này không đọc nó.
     *
     * <p>Bản cũ đóng cả hai kênh, nên một môi trường chưa cấu hình SMTP làm người được mời
     * không nhận được email mà cũng không thấy lời mời trong app — đúng sự cố team FE báo.</p>
     */
    @Test
    void disablingEmailStillRaisesTheInAppNotification() {
        properties.setEmailEnabled(false);
        UserEntity invitee = new UserEntity();
        invitee.setId("user-1");
        when(users.findByEmailIgnoreCase("an@example.com")).thenReturn(Optional.of(invitee));

        notifier.notifyInApp(invitation("an@example.com", null), "Mai");

        verify(notifications).publish(eq("user-1"), eq(NotificationType.FAMILY), anyString(),
                anyString(), anyString(), eq("FAMILY_INVITATION"), any());
    }

    /**
     * Deep link không được mang token thô — bản cũ ghi {@code ?token=<raw>} thẳng vào
     * {@code notifications.deep_link}, tức là cất một token dùng được trong DB dưới dạng chữ
     * thường, vô hiệu hoá chính lý do tồn tại của cột {@code token_hash}.
     *
     * <p>Kênh này không còn nhận {@code rawToken} nữa, nên lỗi cũ không quay lại được bằng
     * cấu trúc; bài này chỉ là lưới đỡ. Ở đây chỉ khẳng định được <em>hình dạng</em>:
     * {@code @PrePersist} không chạy trong test Mockito thuần nên {@code getId()} là null.
     * Việc id thật sự được nội suy đúng do {@code FamilyInvitationDeliveryIntegrationTest}
     * ghim, nơi lời mời đi qua DB thật.</p>
     */
    @Test
    void theInAppDeepLinkCarriesNoRawToken() {
        UserEntity invitee = new UserEntity();
        invitee.setId("user-1");
        when(users.findByEmailIgnoreCase("an@example.com")).thenReturn(Optional.of(invitee));
        ArgumentCaptor<String> deepLink = ArgumentCaptor.forClass(String.class);

        notifier.notifyInApp(invitation("an@example.com", null), "Mai");

        verify(notifications).publish(anyString(), any(), anyString(), anyString(),
                deepLink.capture(), anyString(), any());
        assertThat(deepLink.getValue())
                .startsWith("nutrimom://family/invitations/")
                .doesNotContain("token=");
    }

    private static FamilyInvitationEntity invitation(String invitedEmail, String invitedPhone) {
        FamilyInvitationEntity invitation = new FamilyInvitationEntity();
        invitation.setInvitedEmail(invitedEmail);
        invitation.setInvitedPhone(invitedPhone);
        invitation.setRelationship(FamilyRelationship.PARTNER);
        invitation.setScopes(EnumSet.of(FamilyScope.SHARED_CALENDAR));
        invitation.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(48));
        return invitation;
    }
}
