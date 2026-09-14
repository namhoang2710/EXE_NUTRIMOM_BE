package vn.nutrimom.dashboard;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.family.domain.FamilyTaskEntity;
import vn.nutrimom.family.domain.FamilyTaskPriority;
import vn.nutrimom.family.domain.FamilyTaskStatus;
import vn.nutrimom.family.repository.FamilyTaskRepository;
import vn.nutrimom.support.ApiIntegrationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartnerDashboardIntegrationTest extends ApiIntegrationTestSupport {
    @Autowired FamilyTaskRepository tasks;

    @Test
    void accountWithoutMembershipCannotOpenPartnerDashboard() throws Exception {
        Session user = registerViaOtp("0912380001", "No Membership");

        mockMvc.perform(get("/api/v1/dashboard/partner")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SHARING_SCOPE_REQUIRED"));
    }

    @Test
    void partnerDashboardAppliesCurrentScopesAndIgnoresForeignPregnancyId() throws Exception {
        Session ownerA = registerViaOtp("0912380011", "Partner Owner A");
        Session partner = registerViaOtp("0912380012", "Scoped Partner");
        Session ownerB = registerViaOtp("0912380013", "Partner Owner B");
        Group groupA = createPregnancyAndGroup(ownerA, 100);
        Group groupB = createPregnancyAndGroup(ownerB, 150);
        String token = createInvitation(
                ownerA, partner.phone(), Set.of("FAMILY_TASKS"));
        MvcResult accepted = accept(partner, token);
        JsonNode member = objectMapper.readTree(
                accepted.getResponse().getContentAsString()).at("/data");
        String memberId = member.at("/id").stringValue();

        FamilyTaskEntity task = new FamilyTaskEntity();
        task.setFamilyGroupId(groupA.groupId());
        task.setTitle("Prepare hospital bag");
        task.setDescription("Shared family task");
        task.setPriority(FamilyTaskPriority.HIGH);
        task.setAssigneeId(memberId);
        task.setStatus(FamilyTaskStatus.TODO);
        tasks.saveAndFlush(task);

        mockMvc.perform(get("/api/v1/dashboard/partner")
                        .queryParam("pregnancy_id", groupB.pregnancyId())
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membership_role").value("PARTNER"))
                .andExpect(jsonPath("$.data.pregnancy_overview").doesNotExist())
                .andExpect(jsonPath("$.data.assigned_tasks[0].id").value(task.getId()))
                .andExpect(jsonPath("$.data.assigned_tasks[0].title")
                        .value("Prepare hospital bag"))
                .andExpect(jsonPath("$.data.shared_calendar").doesNotExist())
                .andExpect(jsonPath("$.data.allowed_alerts").doesNotExist())
                .andExpect(jsonPath("$.data.activity_feed").doesNotExist())
                .andExpect(jsonPath("$.data.medical_records").doesNotExist());

        mockMvc.perform(patch("/api/v1/family-members/{memberId}", memberId)
                        .header("Authorization", "Bearer " + ownerA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"PREGNANCY_SUMMARY\"],\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopes", hasItem("PREGNANCY_SUMMARY")))
                .andExpect(jsonPath("$.data.scopes", not(hasItem("FAMILY_TASKS"))));

        mockMvc.perform(get("/api/v1/dashboard/partner")
                        .queryParam("pregnancy_id", groupB.pregnancyId())
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pregnancy_overview.id")
                        .value(groupA.pregnancyId()))
                .andExpect(jsonPath("$.data.pregnancy_overview.id")
                        .value(not(groupB.pregnancyId())))
                .andExpect(jsonPath("$.data.assigned_tasks").doesNotExist())
                .andExpect(jsonPath("$.data.medical_records").doesNotExist());

        mockMvc.perform(delete("/api/v1/family-members/{memberId}", memberId)
                        .header("Authorization", "Bearer " + ownerA.accessToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/dashboard/partner")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SHARING_SCOPE_REQUIRED"));
    }

    private Group createPregnancyAndGroup(Session owner, int daysUntilDue) throws Exception {
        LocalDate dueDate = LocalDate.now(ZoneOffset.UTC).plusDays(daysUntilDue);
        MvcResult pregnancyResult = mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PregnancyBody(dueDate))))
                .andExpect(status().isCreated())
                .andReturn();
        String pregnancyId = objectMapper.readTree(
                pregnancyResult.getResponse().getContentAsString())
                .at("/data/id").stringValue();
        MvcResult groupResult = mockMvc.perform(post("/api/v1/family-groups")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String groupId = objectMapper.readTree(groupResult.getResponse().getContentAsString())
                .at("/data/id").stringValue();
        return new Group(pregnancyId, groupId);
    }

    private String createInvitation(
            Session owner, String invitedPhone, Set<String> scopes) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/family-invitations")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitationBody(
                                invitedPhone, "PARTNER", scopes, 48))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/token").stringValue();
    }

    private MvcResult accept(Session partner, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/family-invitations/accept")
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptBody(token))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private record PregnancyBody(LocalDate estimatedDueDate) { }
    private record InvitationBody(
            String invitedPhone, String relationship, Set<String> scopes, int expiresInHours) { }
    private record AcceptBody(String token) { }
    private record Group(String pregnancyId, String groupId) { }
}
