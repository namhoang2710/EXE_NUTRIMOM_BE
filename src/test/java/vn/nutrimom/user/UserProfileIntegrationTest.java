package vn.nutrimom.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfileIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void verifyResponseAndProfileExposeOnboardingStatus() throws Exception {
        String access = registerViaOtp("0912345001", "Mẹ An");

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("+84912345001"))
                .andExpect(jsonPath("$.data.display_name").value("Mẹ An"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.onboarding_status").value("PROFILE_REQUIRED"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void patchUpdatesProfileAndAdvancesOnboarding() throws Exception {
        String access = registerViaOtp("0912345002", "Mẹ Hoa");

        mockMvc.perform(patch("/api/v1/users/me").header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"display_name":"Nguyễn Thị Hoa","gender":"FEMALE",
                     "date_of_birth":"1997-04-18","email":"hoa@example.com","version":0}
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.display_name").value("Nguyễn Thị Hoa"))
                .andExpect(jsonPath("$.data.gender").value("FEMALE"))
                .andExpect(jsonPath("$.data.date_of_birth").value("1997-04-18"))
                .andExpect(jsonPath("$.data.onboarding_status").value("CONTEXT_REQUIRED"))
                .andExpect(jsonPath("$.data.version").value(1));

        // Thay đổi được lưu bền vững qua lần đọc sau.
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("hoa@example.com"))
                .andExpect(jsonPath("$.data.onboarding_status").value("CONTEXT_REQUIRED"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void patchWithStaleVersionReturnsConflict() throws Exception {
        String access = registerViaOtp("0912345003", "Mẹ Lan");

        mockMvc.perform(patch("/api/v1/users/me").header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"display_name":"Mẹ Lan Mới","version":999}
                    """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void patchWithFutureDateOfBirthReturnsValidationError() throws Exception {
        String access = registerViaOtp("0912345004", "Mẹ Mai");

        mockMvc.perform(patch("/api/v1/users/me").header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"date_of_birth":"2999-01-01","version":0}
                    """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.dateOfBirth").exists());
    }

    @Test
    void eachTokenOnlySeesOwnProfile() throws Exception {
        String tokenA = registerViaOtp("0912345005", "Mẹ A");
        String tokenB = registerViaOtp("0912345006", "Mẹ B");

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.data.phone").value("+84912345005"));
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.data.phone").value("+84912345006"));
    }

    /** Đăng ký user mới qua OTP flow và trả access token; đồng thời khẳng định verify response mang onboarding_status. */
    private String registerViaOtp(String phone, String displayName) throws Exception {
        MvcResult requested = mockMvc.perform(post("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new OtpRequestBody(phone, "REGISTER", true, "profile-device"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode challenge = objectMapper.readTree(requested.getResponse().getContentAsString());
        String challengeId = challenge.at("/data/challenge_id").stringValue();
        String code = challenge.at("/data/debug_code").stringValue();

        MvcResult verified = mockMvc.perform(post("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new VerifyOtpBody(challengeId, code, "profile-device", displayName))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.new_user").value(true))
                .andExpect(jsonPath("$.data.authentication.user.onboarding_status").value("PROFILE_REQUIRED"))
                .andReturn();
        return objectMapper.readTree(verified.getResponse().getContentAsString())
                .at("/data/authentication/access_token").stringValue();
    }

    private record OtpRequestBody(String phone, String purpose, boolean acceptedTerms, String deviceId) { }
    private record VerifyOtpBody(String challengeId, String code, String deviceId, String displayName) { }
}
