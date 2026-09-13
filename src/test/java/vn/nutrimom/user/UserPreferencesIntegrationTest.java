package vn.nutrimom.user;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.isIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class UserPreferencesIntegrationTest extends ApiIntegrationTestSupport {

    @Test
    void getCreatesDefaultsAndPatchUsesOptimisticLocking() throws Exception {
        Session session = registerViaOtp("0912351001", "Preference User");

        mockMvc.perform(get("/api/v1/users/me/preferences")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.language").value("vi"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.data.notification_enabled").value(true))
                .andExpect(jsonPath("$.data.version").value(0));

        mockMvc.perform(patch("/api/v1/users/me/preferences")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language":"en","timezone":"Asia/Singapore",
                                 "push_enabled":false,"preferred_reminder_time":"07:30:00",
                                 "version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.language").value("en"))
                .andExpect(jsonPath("$.data.push_enabled").value(false))
                .andExpect(jsonPath("$.data.preferred_reminder_time").value("07:30:00"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(patch("/api/v1/users/me/preferences")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sms_enabled\":true,\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void preferencesAreIsolatedByAuthenticatedUser() throws Exception {
        Session userA = registerViaOtp("0912351002", "Preference A");
        Session userB = registerViaOtp("0912351003", "Preference B");
        getPreferences(userA);
        getPreferences(userB);

        mockMvc.perform(patch("/api/v1/users/me/preferences")
                        .header("Authorization", "Bearer " + userA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"en\",\"version\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me/preferences")
                        .header("Authorization", "Bearer " + userB.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.language").value("vi"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void referenceRolesExcludePrivilegedRoles() throws Exception {
        Session session = registerViaOtp("0912351004", "Reference User");

        mockMvc.perform(get("/api/v1/reference-data/roles")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code",
                        everyItem(not(isIn(new String[]{"ADMIN", "EXPERT"})))))
                .andExpect(jsonPath("$.data[0].display_name").exists())
                .andExpect(jsonPath("$.data[0].description").exists())
                .andExpect(jsonPath("$.data[0].allowed_relationships").isArray())
                .andExpect(jsonPath("$.data[0].sort_order").isNumber());
    }

    private void getPreferences(Session session) throws Exception {
        mockMvc.perform(get("/api/v1/users/me/preferences")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());
    }
}
