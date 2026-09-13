package vn.nutrimom.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class ApiIntegrationTestSupport {
    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;

    protected Session registerViaOtp(String phone, String displayName) throws Exception {
        MvcResult requested = mockMvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OtpRequestBody(phone, "REGISTER", true, "integration-device"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode challenge = objectMapper.readTree(requested.getResponse().getContentAsString());

        MvcResult verified = mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyOtpBody(
                                challenge.at("/data/challenge_id").stringValue(),
                                challenge.at("/data/debug_code").stringValue(),
                                "integration-device", displayName))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode authentication = objectMapper.readTree(verified.getResponse().getContentAsString())
                .at("/data/authentication");
        return new Session(
                authentication.at("/access_token").stringValue(),
                authentication.at("/refresh_token").stringValue(),
                authentication.at("/user/id").stringValue(),
                phone);
    }

    protected Session registerWithPassword(String phone, String password, String displayName)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordRegistrationBody(
                                        phone, password, displayName, "integration-device"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode authentication = objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data");
        return new Session(
                authentication.at("/access_token").stringValue(),
                authentication.at("/refresh_token").stringValue(),
                authentication.at("/user/id").stringValue(),
                phone);
    }

    protected OtpChallenge requestLoginOtp(String phone) throws Exception {
        MvcResult requested = mockMvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OtpRequestBody(phone, "LOGIN", false, "integration-device"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode challenge = objectMapper.readTree(requested.getResponse().getContentAsString());
        return new OtpChallenge(
                challenge.at("/data/challenge_id").stringValue(),
                challenge.at("/data/debug_code").stringValue());
    }

    protected record Session(String accessToken, String refreshToken, String userId, String phone) { }
    protected record OtpChallenge(String id, String code) { }
    private record OtpRequestBody(String phone, String purpose,
                                  boolean acceptedTerms, String deviceId) { }
    private record VerifyOtpBody(String challengeId, String code,
                                 String deviceId, String displayName) { }
    private record PasswordRegistrationBody(String phone, String password,
                                            String displayName, String deviceId) { }
}
