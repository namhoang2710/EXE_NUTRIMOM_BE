package vn.nutrimom.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.dto.ActivateAccountRequest;
import vn.nutrimom.auth.repository.*;
import vn.nutrimom.common.email.EmailService;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.notification.service.AdminNotificationEvents;
import vn.nutrimom.user.service.UserPersonaService;

@ExtendWith(MockitoExtension.class)
class ActivationSecurityTest {
    @Mock UserRepository users;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock PasswordEncoder passwords;
    @Mock AuthenticationManager authentication;
    @Mock PhoneNormalizer phones;
    @Mock TokenService tokens;
    @Mock UserPersonaService personas;
    @Mock EmailService email;
    @Mock AdminNotificationEvents adminEvents;
    private AuthService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, refreshTokens, passwords, authentication, phones,
                tokens, personas, email, "http://localhost:5173/auth/verify", adminEvents);
        user = new UserEntity();
        user.setId("activation-fixture");
        user.setDisplayName("Fixture");
        user.setEmail("fixture@example.test");
        user.setStatus(UserStatus.PENDING_ACTIVATION);
        user.setEmailActivationTokenHash("valid-hash");
        user.setEmailActivationExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        when(tokens.hash(anyString())).thenReturn("valid-hash");
    }

    private ActivateAccountRequest request() {
        when(users.findByEmailIgnoreCaseForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        return new ActivateAccountRequest("123456", user.getEmail(), "fixture-device");
    }

    private void assertRejected(ActivateAccountRequest request, String code) {
        assertThatThrownBy(() -> service.activateAccount(request))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(code));
        verify(tokens, never()).issue(any(), any());
        verify(users, never()).save(any());
    }

    @Test void activeAccountWithAnyCodeCannotIssueSession() {
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailActivationTokenHash(null);
        assertRejected(request(), "INVALID_ACTIVATION_TOKEN");
    }

    @Test void activeAccountWithStaleMatchingCodeCannotIssueSession() {
        user.setStatus(UserStatus.ACTIVE);
        assertRejected(request(), "INVALID_ACTIVATION_TOKEN");
    }

    @Test void disabledAccountCannotBeReactivatedByCode() {
        user.setStatus(UserStatus.DISABLED);
        assertRejected(request(), "INVALID_ACTIVATION_TOKEN");
    }

    @Test void lockedAccountCannotBeReactivatedByCode() {
        user.setStatus(UserStatus.LOCKED);
        assertRejected(request(), "INVALID_ACTIVATION_TOKEN");
    }

    @Test void wrongCodeCannotActivatePendingAccount() {
        user.setEmailActivationTokenHash("other-hash");
        assertRejected(request(), "INVALID_ACTIVATION_TOKEN");
    }

    @Test void expiredCodeCannotActivateAccount() {
        user.setEmailActivationExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        assertRejected(request(), "ACTIVATION_TOKEN_EXPIRED");
    }

    @Test void missingExpiryCannotActivateAccount() {
        user.setEmailActivationExpiresAt(null);
        assertRejected(request(), "ACTIVATION_TOKEN_EXPIRED");
    }

    @Test void validCodeIsConsumedBeforeIssuingSessionAndReplayIsRejected() {
        var request = request();
        when(tokens.issue(user, "fixture-device")).thenReturn(new TokenService.IssuedToken(
                new TokenBundle("fixture-access", 60, "fixture-refresh", 120, "Bearer"), new RefreshTokenEntity()));
        var result = service.activateAccount(request);
        assertThat(result.accessToken()).isEqualTo("fixture-access");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getEmailActivationTokenHash()).isNull();
        assertThat(user.getEmailActivationExpiresAt()).isNull();
        assertThat(user.getEmailVerifiedAt()).isNotNull();
        var order = inOrder(users, tokens);
        order.verify(users).save(user);
        order.verify(tokens).issue(user, "fixture-device");
        assertThatThrownBy(() -> service.activateAccount(request)).isInstanceOf(BusinessException.class);
        verify(tokens, times(1)).issue(any(), any());
    }

    @Test void tokenOnlyFlowAlsoUsesLockedLookupAndRejectsUsedAccount() {
        user.setStatus(UserStatus.ACTIVE);
        when(users.findByEmailActivationTokenHashForUpdate("valid-hash")).thenReturn(Optional.of(user));
        assertRejected(new ActivateAccountRequest("123456", null, "fixture-device"), "INVALID_ACTIVATION_TOKEN");
    }
}
