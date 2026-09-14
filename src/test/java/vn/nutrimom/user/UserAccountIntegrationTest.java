package vn.nutrimom.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import vn.nutrimom.support.ApiIntegrationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAccountIntegrationTest extends ApiIntegrationTestSupport {

    @Test
    void deletionRequiresReauthentication() throws Exception {
        Session session = registerViaOtp("0912352001", "Delete Guard");

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"No longer needed\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));
    }

    @Test
    void passwordDeletionDisablesLoginAndRevokesRefreshTokens() throws Exception {
        String password = "DeleteMe123!";
        Session session = registerWithPassword("0912352002", password, "Password Delete");

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DeleteBody("No longer needed", password, null, null))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.deletion_request_id").isString())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefreshBody(session.refreshToken(), "integration-device"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginBody("0912352002", password, "integration-device"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otpOnlyAccountCanBeDeletedWithLoginOtp() throws Exception {
        Session session = registerViaOtp("0912352003", "OTP Delete");
        OtpChallenge challenge = requestLoginOtp("0912352003");

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeleteBody(
                                "Privacy request", null, challenge.id(), challenge.code()))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new VerifyBody(challenge.id(), challenge.code(),
                                        "integration-device", null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("OTP_CHALLENGE_USED"));
    }

    private record DeleteBody(String reason, String password,
                              String otpChallengeId, String otpCode) { }
    private record RefreshBody(String refreshToken, String deviceId) { }
    private record LoginBody(String phone, String password, String deviceId) { }
    private record VerifyBody(String challengeId, String code,
                              String deviceId, String displayName) { }
}
