package vn.nutrimom.family;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import vn.nutrimom.support.ApiIntegrationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FamilyTaskIntegrationTest extends ApiIntegrationTestSupport {

    @Test
    void ownerCreatesListsAndFiltersTasks() throws Exception {
        Session owner = registerViaOtp("0913000001", "Task Owner");
        createPregnancyAndGroup(owner);

        String taskId = createTask(owner,
                "{\"title\":\"Buy vitamins\",\"priority\":\"HIGH\",\"description\":\"prenatal\"}");

        mockMvc.perform(get("/api/v1/family/tasks")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(taskId))
                .andExpect(jsonPath("$.data[0].status").value("TODO"))
                .andExpect(jsonPath("$.data[0].priority").value("HIGH"));

        mockMvc.perform(get("/api/v1/family/tasks?status=TODO")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(taskId));

        mockMvc.perform(get("/api/v1/family/tasks?status=COMPLETED")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void memberWithTaskScopeCanCompleteAndDelete() throws Exception {
        Session owner = registerViaOtp("0913000011", "Scope Owner");
        Session partner = registerViaOtp("0913000012", "Scope Partner");
        createPregnancyAndGroup(owner);
        inviteAndAccept(owner, partner, Set.of("FAMILY_TASKS"));

        String taskId = createTask(partner,
                "{\"title\":\"Prep hospital bag\",\"priority\":\"MEDIUM\"}");

        mockMvc.perform(patch("/api/v1/family/tasks/{id}", taskId)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(delete("/api/v1/family/tasks/{id}", taskId)
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/family/tasks")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void memberWithoutTaskScopeIsForbidden() throws Exception {
        Session owner = registerViaOtp("0913000021", "NoScope Owner");
        Session partner = registerViaOtp("0913000022", "NoScope Partner");
        createPregnancyAndGroup(owner);
        inviteAndAccept(owner, partner, Set.of("PREGNANCY_SUMMARY"));

        mockMvc.perform(get("/api/v1/family/tasks")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SHARING_SCOPE_REQUIRED"));

        mockMvc.perform(post("/api/v1/family/tasks")
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nope\",\"priority\":\"LOW\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SHARING_SCOPE_REQUIRED"));
    }

    @Test
    void callerWithoutGroupGetsNotFoundAndCannotReachOtherGroups() throws Exception {
        Session owner = registerViaOtp("0913000031", "Owner A");
        Session outsider = registerViaOtp("0913000032", "Outsider");
        createPregnancyAndGroup(owner);
        String taskId = createTask(owner, "{\"title\":\"Private\",\"priority\":\"LOW\"}");

        mockMvc.perform(get("/api/v1/family/tasks")
                        .header("Authorization", "Bearer " + outsider.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FAMILY_GROUP_NOT_FOUND"));

        // Outsider owns their own group; still cannot touch owner A's task (row-level 404).
        createPregnancyAndGroup(outsider);
        mockMvc.perform(patch("/api/v1/family/tasks/{id}", taskId)
                        .header("Authorization", "Bearer " + outsider.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\",\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void staleVersionIsRejected() throws Exception {
        Session owner = registerViaOtp("0913000041", "Version Owner");
        createPregnancyAndGroup(owner);
        String taskId = createTask(owner, "{\"title\":\"Lock me\",\"priority\":\"LOW\"}");

        mockMvc.perform(patch("/api/v1/family/tasks/{id}", taskId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"version\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void reassignValidatesAssigneeBelongsToGroup() throws Exception {
        Session owner = registerViaOtp("0913000051", "Assign Owner");
        Session partner = registerViaOtp("0913000052", "Assign Partner");
        createPregnancyAndGroup(owner);
        String memberId = inviteAndAccept(owner, partner, Set.of("FAMILY_TASKS"));
        String taskId = createTask(owner, "{\"title\":\"Assignable\",\"priority\":\"LOW\"}");

        mockMvc.perform(patch("/api/v1/family/tasks/{id}", taskId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee_id\":\"" + memberId + "\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignee_id").value(memberId));

        mockMvc.perform(get("/api/v1/family/tasks?assignee_id=" + memberId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(taskId));

        mockMvc.perform(patch("/api/v1/family/tasks/{id}", taskId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee_id\":\"11111111-1111-1111-1111-111111111111\",\"version\":1}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    private String createTask(Session session, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/family/tasks")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/id").stringValue();
    }

    private void createPregnancyAndGroup(Session owner) throws Exception {
        LocalDate dueDate = LocalDate.now(ZoneOffset.UTC).plusDays(100);
        mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PregnancyBody(dueDate))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/family-groups")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
    }

    private String inviteAndAccept(Session owner, Session partner, Set<String> scopes)
            throws Exception {
        MvcResult invited = mockMvc.perform(post("/api/v1/family-invitations")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new InvitationBody(partner.phone(), "PARTNER", scopes, 48))))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(invited.getResponse().getContentAsString())
                .at("/data/token").stringValue();
        ResultActions accepted = mockMvc.perform(post("/api/v1/family-invitations/accept")
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptBody(token))))
                .andExpect(status().isOk());
        return objectMapper.readTree(accepted.andReturn().getResponse().getContentAsString())
                .at("/data/id").stringValue();
    }

    private record PregnancyBody(LocalDate estimatedDueDate) { }
    private record InvitationBody(
            String invitedPhone, String relationship, Set<String> scopes, int expiresInHours) { }
    private record AcceptBody(String token) { }
}
