package vn.nutrimom.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.bootstrap.AdminUserInitializer;

@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=false",
        "app.bootstrap.admin-user-enabled=true",
        "app.bootstrap.admin-phone=0900000001",
        "app.bootstrap.admin-password=Admin123456",
        "app.bootstrap.admin-display-name=Local Admin"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuthIntegrationTest {
    private static final String ADMIN_PHONE = "0900000001";
    private static final String NORMALIZED_ADMIN_PHONE = "+84900000001";
    private static final String ADMIN_PASSWORD = "Admin123456";
    private static final String NORMALIZED_USER_PHONE = "+84900000002";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AdminUserInitializer adminUserInitializer;

    @Test
    void adminCanLoginAndJwtContainsAdminRole() throws Exception {
        MvcResult result = login(ADMIN_PHONE, ADMIN_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.roles", hasItem("ADMIN")))
                .andReturn();

        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/access_token").stringValue();
        Jwt jwt = jwtDecoder.decode(accessToken);

        assertThat(jwt.getClaimAsStringList("roles")).contains("ADMIN");
    }

    @Test
    void normalUserCannotAccessAdminEndpoint() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"0900000002","password":"UserTest123!",
                                 "display_name":"Normal User","device_id":"admin-access-test"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = objectMapper.readTree(registration.getResponse().getContentAsString())
                .at("/data/access_token").stringValue();

        adminUserInitializer.run(null);

        UserEntity normalUser = users.findByPhone(NORMALIZED_USER_PHONE).orElseThrow();
        assertThat(normalUser.getRoles()).containsExactly(UserRole.USER);
        mockMvc.perform(get("/api/v1/admin/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void adminCanAccessAdminEndpoint() throws Exception {
        String accessToken = accessToken(login(ADMIN_PHONE, ADMIN_PASSWORD).andReturn());

        mockMvc.perform(get("/api/v1/admin/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value(NORMALIZED_ADMIN_PHONE))
                .andExpect(jsonPath("$.data.roles", hasItem("ADMIN")));
    }

    @Test
    void existingUserGetsAdminAccessAndNewPasswordWithoutRehashLoop() throws Exception {
        UserEntity existing = users.findByPhone(NORMALIZED_ADMIN_PHONE).orElseThrow();
        existing.setPasswordHash(passwordEncoder.encode("OldAdmin123"));
        existing.setRoles(Set.of(UserRole.USER));
        existing.setStatus(UserStatus.LOCKED);
        users.saveAndFlush(existing);

        adminUserInitializer.run(null);

        UserEntity synchronizedAdmin = users.findByPhone(NORMALIZED_ADMIN_PHONE).orElseThrow();
        assertThat(synchronizedAdmin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(synchronizedAdmin.getRoles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
        assertThat(passwordEncoder.matches(ADMIN_PASSWORD, synchronizedAdmin.getPasswordHash())).isTrue();
        String synchronizedHash = synchronizedAdmin.getPasswordHash();

        login(ADMIN_PHONE, "OldAdmin123").andExpect(status().isUnauthorized());
        login(ADMIN_PHONE, ADMIN_PASSWORD).andExpect(status().isOk());

        adminUserInitializer.run(null);

        UserEntity unchangedAdmin = users.findByPhone(NORMALIZED_ADMIN_PHONE).orElseThrow();
        assertThat(unchangedAdmin.getPasswordHash()).isEqualTo(synchronizedHash);
        assertThat(unchangedAdmin.getRoles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
    }

    private org.springframework.test.web.servlet.ResultActions login(String phone, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new LoginBody(phone, password, "admin-integration-test"))));
    }

    private String accessToken(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.at("/data/access_token").stringValue();
    }

    private record LoginBody(String phone, String password, String deviceId) { }
}
