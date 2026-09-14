package vn.nutrimom.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import vn.nutrimom.family.domain.FamilyInvitationEntity;
import vn.nutrimom.family.repository.FamilyInvitationRepository;
import vn.nutrimom.support.ApiIntegrationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FamilySharingIntegrationTest extends ApiIntegrationTestSupport {
    @Autowired FamilyInvitationRepository invitations;

    @Test
    void ownerCreatesInvitationAndInvitedAccountBecomesPartner() throws Exception {
        Session owner = registerViaOtp("0912370001", "Family Owner");
        Session partner = registerViaOtp("0912370002", "Family Partner");
        Invitation invitation = createInvitation(
                owner, partner.phone(), Set.of("PREGNANCY_SUMMARY", "SHARED_CALENDAR"));

        FamilyInvitationEntity stored = invitations.findById(invitation.id()).orElseThrow();
        assertThat(stored.getTokenHash())
                .hasSize(64)
                .isNotEqualTo(invitation.token());

        mockMvc.perform(post("/api/v1/family-invitations/accept")
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AcceptBody(invitation.token()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membership_role").value("PARTNER"))
                .andExpect(jsonPath("$.data.scopes", hasItem("PREGNANCY_SUMMARY")))
                .andExpect(jsonPath("$.data.scopes", not(hasItem("MEDICAL_RECORDS"))));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("PARTNER"))
                .andExpect(jsonPath("$.data.onboarding_status").value("COMPLETED"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("PARTNER"));
    }

    @Test
    void expiredInvitationIsRejected() throws Exception {
        Session owner = registerViaOtp("0912370011", "Expiry Owner");
        Session partner = registerViaOtp("0912370012", "Expiry Partner");
        Invitation invitation = createInvitation(
                owner, partner.phone(), Set.of("PREGNANCY_SUMMARY"));
        FamilyInvitationEntity stored = invitations.findById(invitation.id()).orElseThrow();
        stored.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        invitations.saveAndFlush(stored);

        mockMvc.perform(post("/api/v1/family-invitations/accept")
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AcceptBody(invitation.token()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("INVITATION_EXPIRED"));
    }

    @Test
    void invitationCanOnlyBeUsedOnce() throws Exception {
        Session owner = registerViaOtp("0912370021", "Once Owner");
        Session partner = registerViaOtp("0912370022", "Once Partner");
        Invitation invitation = createInvitation(
                owner, partner.phone(), Set.of("PREGNANCY_SUMMARY"));

        accept(partner, invitation.token()).andExpect(status().isOk());
        accept(partner, invitation.token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_ALREADY_USED"));
    }

    @Test
    void anonymousOrWrongAccountCannotAcceptInvitation() throws Exception {
        Session owner = registerViaOtp("0912370031", "Target Owner");
        Session invited = registerViaOtp("0912370032", "Target Partner");
        Session wrongAccount = registerViaOtp("0912370033", "Wrong Partner");
        Invitation invitation = createInvitation(
                owner, invited.phone(), Set.of("PREGNANCY_SUMMARY"));

        mockMvc.perform(post("/api/v1/family-invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AcceptBody(invitation.token()))))
                .andExpect(status().isUnauthorized());

        accept(wrongAccount, invitation.token())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INVITATION_TARGET_MISMATCH"));
    }

    @Test
    void onlyOwnerCanChangeScopesAndRevocationTakesEffectImmediately() throws Exception {
        Session owner = registerViaOtp("0912370041", "Scope Owner");
        Session partner = registerViaOtp("0912370042", "Scope Partner");
        Session outsider = registerViaOtp("0912370043", "Scope Outsider");
        Invitation invitation = createInvitation(
                owner, partner.phone(), Set.of("PREGNANCY_SUMMARY"));
        MvcResult accepted = accept(partner, invitation.token())
                .andExpect(status().isOk())
                .andReturn();
        String memberId = objectMapper.readTree(accepted.getResponse().getContentAsString())
                .at("/data/id").stringValue();

        mockMvc.perform(patch("/api/v1/family-members/{memberId}", memberId)
                        .header("Authorization", "Bearer " + outsider.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"MEDICAL_RECORDS\"],\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/family-members/{memberId}", memberId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"FAMILY_TASKS\"],\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopes", hasItem("FAMILY_TASKS")))
                .andExpect(jsonPath("$.data.scopes", not(hasItem("PREGNANCY_SUMMARY"))))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(get("/api/v1/family-members")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].scopes", hasItem("FAMILY_TASKS")))
                .andExpect(jsonPath("$.data[0].scopes", not(hasItem("PREGNANCY_SUMMARY"))));

        mockMvc.perform(delete("/api/v1/family-members/{memberId}", memberId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"));
        mockMvc.perform(get("/api/v1/family-members")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private Invitation createInvitation(
            Session owner, String invitedPhone, Set<String> scopes) throws Exception {
        createPregnancyAndGroup(owner);
        MvcResult result = mockMvc.perform(post("/api/v1/family-invitations")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InvitationBody(
                                invitedPhone, "PARTNER", scopes, 48))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
        return new Invitation(data.at("/id").stringValue(), data.at("/token").stringValue());
    }

    private void createPregnancyAndGroup(Session owner) throws Exception {
        LocalDate dueDate = LocalDate.now(ZoneOffset.UTC).plusDays(100);
        mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PregnancyBody(dueDate))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/family-groups")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.owner_user_id").value(owner.userId()));
    }

    private org.springframework.test.web.servlet.ResultActions accept(
            Session user, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/family-invitations/accept")
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AcceptBody(token))));
    }

    private record PregnancyBody(LocalDate estimatedDueDate) { }
    private record InvitationBody(
            String invitedPhone, String relationship, Set<String> scopes, int expiresInHours) { }
    private record AcceptBody(String token) { }
    private record Invitation(String id, String token) { }
}
