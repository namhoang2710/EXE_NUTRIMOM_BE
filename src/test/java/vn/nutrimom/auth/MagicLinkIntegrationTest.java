package vn.nutrimom.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.MagicLoginTokenStatus;
import vn.nutrimom.auth.repository.MagicLoginTokenRepository;
import vn.nutrimom.auth.repository.UserRepository;

@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MagicLinkIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MagicLoginTokenRepository tokenRepository;
    @Autowired UserRepository userRepository;

    @Test
    void testMagicLinkFullFlow() throws Exception {
        String email = "mebau_test@nutrimom.vn";

        // 1. Gửi yêu cầu Magic Link
        MvcResult requestResult = mockMvc.perform(post("/api/v1/auth/magic-link/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","device_id":"test-browser"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent").value(true))
                .andExpect(jsonPath("$.data.debug_link").isNotEmpty())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(requestResult.getResponse().getContentAsString());
        String debugLink = responseNode.at("/data/debug_link").stringValue();
        assertThat(debugLink).contains("?token=");

        String rawToken = debugLink.substring(debugLink.indexOf("?token=") + 7);
        assertThat(rawToken).isNotBlank();

        // Kiểm tra trong DB token ở trạng thái PENDING
        var pendingToken = tokenRepository.findTopByEmailAndStatusOrderByCreatedAtDesc(email, MagicLoginTokenStatus.PENDING);
        assertThat(pendingToken).isPresent();
        assertThat(pendingToken.get().getStatus()).isEqualTo(MagicLoginTokenStatus.PENDING);

        // 2. Xác thực bằng Token vừa nhận
        MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/magic-link/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","device_id":"test-browser"}
                                """.formatted(rawToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").isNotEmpty())
                .andExpect(jsonPath("$.data.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.id").isNotEmpty())
                .andReturn();

        // Kiểm tra sau khi verify, token trong DB chuyển thành USED
        var usedToken = tokenRepository.findById(pendingToken.get().getId()).orElseThrow();
        assertThat(usedToken.getStatus()).isEqualTo(MagicLoginTokenStatus.USED);
        assertThat(usedToken.getUsedAt()).isNotNull();

        // 3. Sử dụng lại token cũ (Replay) -> Phải bị từ chối
        mockMvc.perform(post("/api/v1/auth/magic-link/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","device_id":"test-browser"}
                                """.formatted(rawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void testSixDigitCodeLoginFlow() throws Exception {
        String email = "otp_user@nutrimom.vn";

        // 1. Gửi yêu cầu mã xác thực 6 chữ số
        MvcResult requestResult = mockMvc.perform(post("/api/v1/auth/magic-link/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","device_id":"otp-device"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent").value(true))
                .andExpect(jsonPath("$.data.debug_code").isNotEmpty())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(requestResult.getResponse().getContentAsString());
        String code = responseNode.at("/data/debug_code").stringValue();
        assertThat(code).matches("^\\d{6}$");

        // 2. Xác thực bằng code 6 chữ số và email
        mockMvc.perform(post("/api/v1/auth/magic-link/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","email":"%s","device_id":"otp-device"}
                                """.formatted(code, email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").isNotEmpty())
                .andExpect(jsonPath("$.data.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.id").isNotEmpty())
                .andExpect(jsonPath("$.data.user.display_name").value("otp_user"));
    }

    @Test
    void testMagicLinkInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/magic-link/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
