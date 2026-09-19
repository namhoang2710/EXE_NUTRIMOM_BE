package vn.nutrimom.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.LocaleResolver;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.auth.repository.OtpChallengeRepository;
import vn.nutrimom.support.ApiIntegrationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommonApiContractIntegrationTest extends ApiIntegrationTestSupport {
    @Autowired OtpChallengeRepository challenges;
    @Autowired ConfigurableEnvironment environment;
    @Autowired LocaleResolver localeResolver;

    @Test
    void testProfileDoesNotImportDotEnvFile() {
        assertThat(environment.getPropertySources())
                .allSatisfy(source -> assertThat(source.getName()).doesNotContain("file [.env]"));
    }

    @Test
    void acceptLanguageDefaultsToVietnameseAndHonorsExplicitHeader() {
        MockHttpServletRequest defaultRequest = new MockHttpServletRequest();
        assertThat(localeResolver.resolveLocale(defaultRequest).toLanguageTag())
                .isEqualTo("vi-VN");

        MockHttpServletRequest englishRequest = new MockHttpServletRequest();
        englishRequest.addHeader("Accept-Language", "en-US");
        assertThat(localeResolver.resolveLocale(englishRequest).toLanguageTag())
                .isEqualTo("en-US");
    }

    @Test
    void corsAllowsDocumentedCommonHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/auth/otp/request")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers",
                                "X-Device-Id, Accept-Language, X-Request-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin",
                        "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        "X-Device-Id, Accept-Language, X-Request-Id"));
    }

    @Test
    void successEnvelopeAndOtpDeviceContractUseSnakeCase() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/otp/request")
                        .header("X-Request-Id", "contract-success")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"0912399001","purpose":"REGISTER",
                                 "accepted_terms":true,"device_id":"body-device"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "contract-success"))
                .andExpect(jsonPath("$.data.challenge_id").isNotEmpty())
                .andExpect(jsonPath("$.data.challengeId").doesNotExist())
                .andExpect(jsonPath("$.meta.request_id").value("contract-success"))
                .andExpect(jsonPath("$.meta.server_time").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String challengeId = body.at("/data/challenge_id").stringValue();
        assertThat(challenges.findById(challengeId).orElseThrow().getDeviceId())
                .isEqualTo("body-device");
    }

    @Test
    void securityErrorHasCompleteEnvelopeAndRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header("X-Request-Id", "contract-unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "contract-unauthorized"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.fields").isMap())
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.request_id").value("contract-unauthorized"));
    }

    @Test
    void businessErrorHasCompleteEnvelopeAndRequestId() throws Exception {
        mockMvc.perform(post("/api/v1/auth/otp/request")
                        .header("X-Request-Id", "contract-not-found")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"0912399002","purpose":"LOGIN",
                                 "device_id":"body-device"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "contract-not-found"))
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.fields").isMap())
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.request_id").value("contract-not-found"));
    }
}
