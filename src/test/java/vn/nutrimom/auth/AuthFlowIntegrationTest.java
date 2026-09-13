package vn.nutrimom.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;

@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void otpRegistrationLoginAndOneTimeUse() throws Exception {
        MvcResult requested = mockMvc.perform(post("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"phone":"0912345678","purpose":"REGISTER",
                     "accepted_terms":true,"device_id":"otp-device"}
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery_channel").value("DEBUG"))
                .andReturn();
        JsonNode challenge = objectMapper.readTree(requested.getResponse().getContentAsString());
        String challengeId = challenge.at("/data/challenge_id").stringValue();
        String code = challenge.at("/data/debug_code").stringValue();

        MvcResult verified = mockMvc.perform(post("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new VerifyOtpBody(challengeId, code, "otp-device", "Mẹ An"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.new_user").value(true))
                .andExpect(jsonPath("$.data.authentication.user.phone").value("+84912345678"))
                .andExpect(jsonPath("$.data.authentication.user.role").value("USER"))
                .andReturn();
        String access = objectMapper.readTree(verified.getResponse().getContentAsString())
                .at("/data/authentication/access_token").stringValue();
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.display_name").value("Mẹ An"))
                .andExpect(jsonPath("$.data.role").value("USER"));
        mockMvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new VerifyOtpBody(challengeId, code, "otp-device", null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("OTP_CHALLENGE_USED"));

        MvcResult loginRequest = mockMvc.perform(post("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"phone":"0912345678","purpose":"LOGIN","device_id":"otp-device"}
                    """))
                .andExpect(status().isOk()).andReturn();
        JsonNode login = objectMapper.readTree(loginRequest.getResponse().getContentAsString());
        mockMvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new VerifyOtpBody(
                        login.at("/data/challenge_id").stringValue(),
                        login.at("/data/debug_code").stringValue(), "otp-device", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.new_user").value(false));
    }

    @Test
    void passwordRefreshLogoutAndSwagger() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "register-test")
                .content("""
                    {"phone":"0905551234","password":"Secure123!",
                     "display_name":"Nguyễn An","device_id":"test-device"}
                    """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.role").value("USER"))
                .andExpect(header().string("X-Request-Id", "register-test")).andReturn();
        JsonNode registered = objectMapper.readTree(registration.getResponse().getContentAsString());
        String access = registered.at("/data/access_token").stringValue();
        String refresh = registered.at("/data/refresh_token").stringValue();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        MvcResult rotatedResult = mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshBody(refresh, "test-device"))))
                .andExpect(status().isOk()).andReturn();
        String rotated = objectMapper.readTree(rotatedResult.getResponse().getContentAsString())
                .at("/data/refresh_token").stringValue();
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshBody(refresh, "test-device"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LogoutBody(rotated))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logged_out").value(true));
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("NutriMom Backend API"));
    }

    @Test
    void passwordLoginAndRefreshReturnMomAfterPregnancyCreation() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"0905551250","password":"Secure123!",
                                 "display_name":"Mom Persona","device_id":"persona-device"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.role").value("USER"))
                .andReturn();
        JsonNode registered = objectMapper.readTree(
                registration.getResponse().getContentAsString());
        String access = registered.at("/data/access_token").stringValue();
        String refresh = registered.at("/data/refresh_token").stringValue();
        LocalDate dueDate = LocalDate.now(ZoneOffset.UTC).plusDays(100);

        mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PregnancyBody(dueDate))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"0905551250","password":"Secure123!",
                                 "device_id":"persona-device"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("MOM"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefreshBody(refresh, "persona-device"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("MOM"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("MOM"));

        mockMvc.perform(get("/api/v1/reference-data/roles")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code", not(hasItem("EXPERT"))));
    }

    private record VerifyOtpBody(String challengeId, String code, String deviceId, String displayName) { }
    private record RefreshBody(String refreshToken, String deviceId) { }
    private record LogoutBody(String refreshToken) { }
    private record PregnancyBody(LocalDate estimatedDueDate) { }
}
