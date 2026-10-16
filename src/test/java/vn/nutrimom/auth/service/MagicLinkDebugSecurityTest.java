package vn.nutrimom.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.dto.MagicLinkRequest;
import vn.nutrimom.auth.repository.*;
import vn.nutrimom.common.email.EmailService;
import vn.nutrimom.config.OtpProperties;
import vn.nutrimom.notification.service.AdminNotificationEvents;

@ExtendWith(MockitoExtension.class)
class MagicLinkDebugSecurityTest {
    @Mock MagicLoginTokenRepository challenges;
    @Mock UserRepository users;
    @Mock TokenService tokens;
    @Mock AuthService auth;
    @Mock EmailService mail;
    @Mock AdminNotificationEvents adminEvents;

    private MagicLinkService service(boolean debug, String... profiles) {
        var settings = new OtpProperties();
        settings.setExposeDebugCode(debug);
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        when(tokens.hash(anyString())).thenReturn("fixture-hash");
        return new MagicLinkService(challenges, users, tokens, auth, mail, settings,
                environment, "http://localhost:5173/auth/verify", adminEvents);
    }

    @Test void defaultProfileNeverReturnsOtpEvenWithDebugFlagEnabled() {
        assertHidden(service(true));
    }

    @Test void productionProfileNeverReturnsOtpEvenWithDebugFlagEnabled() {
        assertHidden(service(true, "production"));
    }

    @Test void testProfileRequiresDebugFlagToReturnOtp() {
        assertHidden(service(false, "test"));
    }

    @Test void productionProfileCannotBeOverriddenByAddingTestProfile() {
        assertHidden(service(true, "test", "production"));
    }

    private void assertHidden(MagicLinkService service) {
        var result = service.requestMagicLink(new MagicLinkRequest("fixture@example.test", "fixture-device"), "127.0.0.1");
        assertThat(result.debugCode()).isNull();
        assertThat(result.debugLink()).isNull();
        var json = new ObjectMapper().writeValueAsString(result);
        assertThat(json).doesNotContain("?token=");
        verify(mail).sendEmailOtp(eq("fixture@example.test"), matches("\\d{6}"), contains("?token="));
    }

    @Test void explicitTestFixtureStillSupportsExistingOtpIntegrationTests() {
        var result = service(true, "test").requestMagicLink(
                new MagicLinkRequest("fixture@example.test", "fixture-device"), "127.0.0.1");
        assertThat(result.debugCode()).matches("\\d{6}");
        assertThat(result.debugLink()).endsWith("?token=" + result.debugCode());
    }
}
