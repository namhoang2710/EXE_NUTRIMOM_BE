package vn.nutrimom.dashboard;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.support.ApiIntegrationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MomDashboardIntegrationTest extends ApiIntegrationTestSupport {

    @Test
    void momWithActivePregnancyGetsDashboardWhenOptionalModulesAreMissing() throws Exception {
        Session mom = registerViaOtp("0912360001", "Dashboard Mom");
        String pregnancyId = createPregnancy(mom, LocalDate.now(ZoneOffset.UTC).plusDays(100));

        mockMvc.perform(get("/api/v1/dashboard/mom")
                        .header("Authorization", "Bearer " + mom.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile_summary.id").value(mom.userId()))
                .andExpect(jsonPath("$.data.profile_summary.role").value("MOM"))
                .andExpect(jsonPath("$.data.pregnancy_summary.id").value(pregnancyId))
                .andExpect(jsonPath("$.data.baby_summary").value(nullValue()))
                .andExpect(jsonPath("$.data.next_appointment").value(nullValue()))
                .andExpect(jsonPath("$.data.health_snapshot").value(nullValue()))
                .andExpect(jsonPath("$.data.care_progress").value(nullValue()))
                .andExpect(jsonPath("$.data.active_alerts").isEmpty())
                .andExpect(jsonPath("$.data.recommended_articles").isEmpty())
                .andExpect(jsonPath("$.data.upcoming_reminders").isEmpty())
                .andExpect(jsonPath("$.data.scan_quota").value(nullValue()))
                .andExpect(jsonPath("$.data.subscription").value(nullValue()))
                .andExpect(jsonPath("$.data.unread_notification_count").value(0));
    }

    @Test
    void userWithoutActivePregnancyCannotOpenMomDashboard() throws Exception {
        Session user = registerViaOtp("0912360002", "No Pregnancy");

        mockMvc.perform(get("/api/v1/dashboard/mom")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_PREGNANCY_NOT_FOUND"));
    }

    @Test
    void momDashboardNeverUsesAnotherUsersPregnancy() throws Exception {
        Session momA = registerViaOtp("0912360003", "Dashboard Mom A");
        Session momB = registerViaOtp("0912360004", "Dashboard Mom B");
        String pregnancyA = createPregnancy(
                momA, LocalDate.now(ZoneOffset.UTC).plusDays(90));
        String pregnancyB = createPregnancy(
                momB, LocalDate.now(ZoneOffset.UTC).plusDays(140));

        mockMvc.perform(get("/api/v1/dashboard/mom")
                        .header("Authorization", "Bearer " + momA.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile_summary.id").value(momA.userId()))
                .andExpect(jsonPath("$.data.pregnancy_summary.id").value(pregnancyA))
                .andExpect(jsonPath("$.data.pregnancy_summary.id").value(
                        org.hamcrest.Matchers.not(pregnancyB)));
    }

    private String createPregnancy(Session session, LocalDate dueDate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PregnancyBody(dueDate))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
        return data.at("/id").stringValue();
    }

    private record PregnancyBody(LocalDate estimatedDueDate) { }
}
