package vn.nutrimom.contact;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ContactRequestIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;

    @Test
    void userCreatesAndSeesOwnHistoryButNotOthers() throws Exception {
        String userA = createUserAccount("0913000001", "Mom A", "a@example.com");
        String userB = createUserAccount("0913000002", "Mom B", null);

        String requestId = createContact(userA, "POLICY", "  Chính sách hoàn tiền thế nào?  ");

        mockMvc.perform(get("/api/v1/contact-requests").with(userJwt(userA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(requestId))
                .andExpect(jsonPath("$.data.items[0].message").value("Chính sách hoàn tiền thế nào?"))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING"));

        mockMvc.perform(get("/api/v1/contact-requests/{id}", requestId).with(userJwt(userA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topic").value("POLICY"));

        mockMvc.perform(get("/api/v1/contact-requests/{id}", requestId).with(userJwt(userB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/contact-requests/{id}/cancel", requestId).with(userJwt(userB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/contact-requests").with(userJwt(userB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(0));
    }

    @Test
    void createRejectsMissingTopicOrBlankMessage() throws Exception {
        String userId = createUserAccount("0913000011", "Mom V", null);

        mockMvc.perform(post("/api/v1/contact-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Làm sao đổi mật khẩu?"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/contact-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"ACCOUNT","message":"   "}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void adminListsFiltersViewsDetailAndCompletes() throws Exception {
        String userA = createUserAccount("0913000021", "Nguyen Thi Lan", "lan@example.com");
        String userB = createUserAccount("0913000022", "Tran Thi Hoa", null);
        String lanRequest = createContact(userA, "APP_USAGE", "Không biết dùng nhật ký thai kỳ.");
        createContact(userB, "ACCOUNT", "Quên mật khẩu.");

        mockMvc.perform(get("/api/v1/admin/contact-requests")
                        .param("status", "PENDING").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(2));

        mockMvc.perform(get("/api/v1/admin/contact-requests").param("q", "LAN").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(lanRequest))
                .andExpect(jsonPath("$.data.items[0].user_display_name").value("Nguyen Thi Lan"))
                .andExpect(jsonPath("$.data.items[0].user_phone").value("0913000021"));

        mockMvc.perform(get("/api/v1/admin/contact-requests").param("q", "000022").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].topic").value("ACCOUNT"));

        mockMvc.perform(get("/api/v1/admin/contact-requests").param("topic", "POLICY").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(0));

        mockMvc.perform(get("/api/v1/admin/contact-requests/{id}", lanRequest).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Không biết dùng nhật ký thai kỳ."))
                .andExpect(jsonPath("$.data.user.display_name").value("Nguyen Thi Lan"))
                .andExpect(jsonPath("$.data.user.phone").value("0913000021"))
                .andExpect(jsonPath("$.data.user.email").value("lan@example.com"));

        mockMvc.perform(post("/api/v1/admin/contact-requests/{id}/complete", lanRequest).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completed_by").value("admin"))
                .andExpect(jsonPath("$.data.completed_at").exists());

        mockMvc.perform(post("/api/v1/admin/contact-requests/{id}/complete", lanRequest).with(adminJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_CONTACT_REQUEST_STATE"));

        // User thấy trạng thái đã hoàn tất và không huỷ được nữa
        mockMvc.perform(get("/api/v1/contact-requests/{id}", lanRequest).with(userJwt(userA)))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        mockMvc.perform(post("/api/v1/contact-requests/{id}/cancel", lanRequest).with(userJwt(userA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_CONTACT_REQUEST_STATE"));

        mockMvc.perform(get("/api/v1/admin/contact-requests/{id}", "missing").with(adminJwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void userCancelsPendingAndAdminCannotCompleteIt() throws Exception {
        String userId = createUserAccount("0913000031", "Mom C", null);
        String requestId = createContact(userId, "OTHER", "Muốn góp ý giao diện.");

        mockMvc.perform(post("/api/v1/contact-requests/{id}/cancel", requestId).with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelled_at").exists());

        mockMvc.perform(post("/api/v1/contact-requests/{id}/cancel", requestId).with(userJwt(userId)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/admin/contact-requests/{id}/complete", requestId).with(adminJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_CONTACT_REQUEST_STATE"));
    }

    @Test
    void pendingLimitBlocksSpamUntilOneIsCancelled() throws Exception {
        String userId = createUserAccount("0913000041", "Mom D", null);
        String first = createContact(userId, "POLICY", "Câu hỏi 1");
        createContact(userId, "POLICY", "Câu hỏi 2");
        createContact(userId, "POLICY", "Câu hỏi 3");

        mockMvc.perform(post("/api/v1/contact-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"POLICY","message":"Câu hỏi 4"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTACT_REQUEST_LIMIT_REACHED"));

        mockMvc.perform(post("/api/v1/contact-requests/{id}/cancel", first).with(userJwt(userId)))
                .andExpect(status().isOk());
        createContact(userId, "POLICY", "Câu hỏi 4");
    }

    @Test
    void nonAdminCannotAccessAdminInbox() throws Exception {
        String userId = createUserAccount("0913000051", "Mom E", null);

        mockMvc.perform(get("/api/v1/admin/contact-requests").with(userJwt(userId)))
                .andExpect(status().isForbidden());
    }

    // ----- helpers -----

    private String createContact(String userId, String topic, String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/contact-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("topic", topic, "message", message))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/id").stringValue();
    }

    private String createUserAccount(String phone, String displayName, String email) {
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        return users.saveAndFlush(user).getId();
    }

    private RequestPostProcessor adminJwt() {
        return jwt().jwt(token -> token.subject("admin").claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
