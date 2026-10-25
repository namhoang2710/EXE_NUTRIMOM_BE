package vn.nutrimom.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import vn.nutrimom.assistant.service.AiProviderClient;
import vn.nutrimom.auth.domain.PasswordResetTokenStatus;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.PasswordResetTokenRepository;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.auth.service.TokenService;
import vn.nutrimom.common.email.EmailService;
import vn.nutrimom.common.email.EmailService.MailResult;
import vn.nutrimom.consultation.video.LiveKitGateway;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Quên mật khẩu qua email và đổi mật khẩu khi đang đăng nhập.
 *
 * <p>Thư được gửi ở luồng riêng sau khi commit, nên mọi phép kiểm thư dùng {@code timeout}/{@code after}
 * của Mockito thay vì kiểm ngay.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetIntegrationTest extends ApiIntegrationTestSupport {
    private static final String OLD_PASSWORD = "OldPass1!";
    private static final String NEW_PASSWORD = "NewPass2@";
    private static final long MAIL_WAIT_MS = 3000;

    @MockitoBean EmailService emailService;
    @MockitoBean AiProviderClient assistantProvider;
    @MockitoBean LiveKitGateway videoProvider;
    @Autowired UserRepository users;
    @Autowired PasswordResetTokenRepository resetTokens;
    @Autowired TokenService tokenService;

    @BeforeEach
    void mailAlwaysSucceeds() {
        when(emailService.sendPasswordReset(anyString(), anyString(), anyString(), anyLong())).thenReturn(MailResult.ok());
        when(emailService.sendPasswordChanged(anyString(), anyString())).thenReturn(MailResult.ok());
    }

    @Test
    void theAnswerIsTheSameWhetherOrNotTheEmailBelongsToAnAccount() throws Exception {
        accountWithEmail("0912398001", "known.mom@example.com", UserStatus.ACTIVE);
        accountWithEmail("0912398002", "locked.mom@example.com", UserStatus.LOCKED);

        String known = forgot("Known.Mom@Example.com").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String unknown = forgot("nobody@example.com").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String locked = forgot("locked.mom@example.com").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(data(known)).isEqualTo(data(unknown)).isEqualTo(data(locked));
        verify(emailService, timeout(MAIL_WAIT_MS).times(1))
                .sendPasswordReset(eq("known.mom@example.com"), anyString(), anyString(), eq(30L));
        verify(emailService, after(500).never()).sendPasswordReset(eq("nobody@example.com"), anyString(), anyString(), anyLong());
        verify(emailService, after(100).never()).sendPasswordReset(eq("locked.mom@example.com"), anyString(), anyString(), anyLong());
    }

    @Test
    void resettingChangesThePasswordSignsEveryDeviceOutAndCannotBeReplayed(CapturedOutput output) throws Exception {
        Session session = accountWithEmail("0912398003", "reset.mom@example.com", UserStatus.ACTIVE);
        String token = requestToken("reset.mom@example.com");

        call("/api/v1/auth/password/reset/validate", Map.of("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.email_masked").value("r***@example.com"));

        call("/api/v1/auth/password/reset", Map.of("token", token, "new_password", NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reset").value(true))
                .andExpect(jsonPath("$.data.access_token").doesNotExist());

        login("0912398003", OLD_PASSWORD).andExpect(status().isUnauthorized());
        login("reset.mom@example.com", NEW_PASSWORD).andExpect(status().isOk());
        call("/api/v1/auth/refresh", Map.of("refresh_token", session.refreshToken(), "device_id", "integration-device"))
                .andExpect(status().isUnauthorized());
        call("/api/v1/auth/password/reset", Map.of("token", token, "new_password", "Again3#pass"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_TOKEN_INVALID"));
        verify(emailService, timeout(MAIL_WAIT_MS)).sendPasswordChanged(eq("reset.mom@example.com"), anyString());

        // Token thô và email đầy đủ không bao giờ vào log.
        assertThat(output.getAll()).doesNotContain(token).doesNotContain("reset.mom@example.com");
    }

    @Test
    void expiredAndSupersededLinksAreRejected() throws Exception {
        accountWithEmail("0912398004", "expired.mom@example.com", UserStatus.ACTIVE);
        String first = requestToken("expired.mom@example.com");

        var stored = resetTokens.findByTokenHash(tokenService.hash(first)).orElseThrow();
        stored.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        resetTokens.saveAndFlush(stored);
        call("/api/v1/auth/password/reset/validate", Map.of("token", first))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_TOKEN_EXPIRED"));

        // Lùi mốc tạo của token cũ để qua cooldown, rồi xin link mới: link cũ (dù chưa hết hạn) bị thay.
        stored.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        stored.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        resetTokens.saveAndFlush(stored);
        String second = requestToken("expired.mom@example.com");
        assertThat(second).isNotEqualTo(first);
        assertThat(resetTokens.findByTokenHash(tokenService.hash(first)).orElseThrow().getStatus())
                .isEqualTo(PasswordResetTokenStatus.REVOKED);
        call("/api/v1/auth/password/reset", Map.of("token", first, "new_password", NEW_PASSWORD))
                .andExpect(status().isBadRequest());
        call("/api/v1/auth/password/reset", Map.of("token", "khong-ton-tai", "new_password", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_TOKEN_INVALID"));
    }

    @Test
    void aWeakNewPasswordIsRejectedAndTheLinkStillWorks() throws Exception {
        accountWithEmail("0912398005", "weak.mom@example.com", UserStatus.ACTIVE);
        String token = requestToken("weak.mom@example.com");

        call("/api/v1/auth/password/reset", Map.of("token", token, "new_password", "password"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        call("/api/v1/auth/password/reset", Map.of("token", token, "new_password", NEW_PASSWORD))
                .andExpect(status().isOk());
    }

    @Test
    void repeatedRequestsAreThrottledWithoutChangingTheAnswer() throws Exception {
        accountWithEmail("0912398006", "spam.mom@example.com", UserStatus.ACTIVE);
        String first = data(forgot("spam.mom@example.com").andReturn().getResponse().getContentAsString());
        verify(emailService, timeout(MAIL_WAIT_MS).times(1))
                .sendPasswordReset(eq("spam.mom@example.com"), anyString(), anyString(), anyLong());

        String second = data(forgot("spam.mom@example.com").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(second).isEqualTo(first);
        verify(emailService, after(500).times(1))
                .sendPasswordReset(eq("spam.mom@example.com"), anyString(), anyString(), anyLong());
    }

    @Test
    void aPendingAccountIsActivatedByResettingItsPassword() throws Exception {
        accountWithEmail("0912398007", "pending.mom@example.com", UserStatus.PENDING_ACTIVATION);
        String token = requestToken("pending.mom@example.com");

        call("/api/v1/auth/password/reset", Map.of("token", token, "new_password", NEW_PASSWORD))
                .andExpect(status().isOk());
        UserEntity user = users.findByEmailIgnoreCase("pending.mom@example.com").orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getEmailVerifiedAt()).isNotNull();
        login("pending.mom@example.com", NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void oneLinkUsedTwiceAtTheSameTimeChangesThePasswordOnce() throws Exception {
        accountWithEmail("0912398008", "race.mom@example.com", UserStatus.ACTIVE);
        String token = requestToken("race.mom@example.com");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (String password : List.of(NEW_PASSWORD, "Other4$pass")) {
            results.add(pool.submit(() -> {
                start.await();
                return call("/api/v1/auth/password/reset", Map.of("token", token, "new_password", password))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) statuses.add(result.get());
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
    }

    @Test
    void changingThePasswordNeedsTheCurrentOneAndSignsEveryDeviceOut() throws Exception {
        Session session = registerWithPassword("0912398009", OLD_PASSWORD, "Change Mom");
        String otherDeviceRefresh = refreshTokenOf(login("0912398009", OLD_PASSWORD));

        change(session, "WrongPass1!", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INCORRECT"));
        change(session, OLD_PASSWORD, OLD_PASSWORD)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.fields.new_password").exists());
        change(session, OLD_PASSWORD, "weak")
                .andExpect(status().isUnprocessableContent());
        change(session, OLD_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        login("0912398009", NEW_PASSWORD).andExpect(status().isOk());
        call("/api/v1/auth/refresh", Map.of("refresh_token", otherDeviceRefresh, "device_id", "integration-device"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/password/change").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("current_password", OLD_PASSWORD, "new_password", NEW_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAccountWithoutAPasswordIsSentToTheForgotFlow() throws Exception {
        Session session = registerViaOtp("0912398010", "Otp Only Mom");
        change(session, OLD_PASSWORD, NEW_PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_NOT_SET"));
    }

    private Session accountWithEmail(String phone, String email, UserStatus status) throws Exception {
        Session session = registerWithPassword(phone, OLD_PASSWORD, "Reset Mom");
        UserEntity user = users.findById(session.userId()).orElseThrow();
        user.setEmail(email);
        user.setStatus(status);
        if (status == UserStatus.ACTIVE) user.setEmailVerifiedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.saveAndFlush(user);
        return session;
    }

    private String requestToken(String email) throws Exception {
        clearInvocations(emailService);
        forgot(email).andExpect(status().isOk());
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(MAIL_WAIT_MS)).sendPasswordReset(eq(email), anyString(), link.capture(), anyLong());
        assertThat(link.getValue()).startsWith("http://localhost:5173/reset-password?token=");
        return link.getValue().substring(link.getValue().indexOf("token=") + "token=".length());
    }

    private String refreshTokenOf(ResultActions loggedIn) throws Exception {
        return objectMapper.readTree(loggedIn.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .at("/data/refresh_token").stringValue();
    }

    private ResultActions forgot(String email) throws Exception {
        return call("/api/v1/auth/password/forgot", Map.of("email", email));
    }

    private ResultActions login(String phoneOrEmail, String password) throws Exception {
        return call("/api/v1/auth/login", Map.of("phone", phoneOrEmail, "password", password, "device_id", "integration-device"));
    }

    private ResultActions change(Session session, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password/change")
                .header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("current_password", current, "new_password", next))));
    }

    private ResultActions call(String path, Map<String, String> body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String data(String body) throws Exception {
        return objectMapper.readTree(body).at("/data").toString();
    }
}
