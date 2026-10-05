package vn.nutrimom.notification;

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
import vn.nutrimom.notification.domain.ActivityType;
import vn.nutrimom.notification.domain.ActivityVisibility;
import vn.nutrimom.notification.service.ActivityFeedService;
import vn.nutrimom.support.ApiIntegrationTestSupport;

/**
 * Phần phân quyền của activity feed: cùng một thai kỳ nhưng thành viên gia đình chỉ thấy hoạt động
 * {@code FAMILY}, và chỉ khi còn scope {@code ACTIVITY_FEED} (spec mục 18 và 22).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityFeedSharingIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired ActivityFeedService activityFeed;

    @Test
    void memberWithScopeSeesSharedActivityButNeverOwnerOnlyOnes() throws Exception {
        Session owner = registerViaOtp("0915000001", "Share Owner");
        Session partner = registerViaOtp("0915000002", "Share Partner");
        Session outsider = registerViaOtp("0915000003", "Outsider");
        String pregnancyId = createPregnancyAndGroup(owner);
        String memberId = inviteAndAccept(owner, partner, Set.of("FAMILY_TASKS", "ACTIVITY_FEED"));

        // Việc nhà: chia sẻ được cho cả nhóm.
        createTaskAssignedTo(owner, memberId);
        // Việc riêng tư của mẹ bầu: cùng thai kỳ nhưng không được lộ sang feed người khác.
        activityFeed.record(owner.userId(), null, pregnancyId, ActivityType.CONSULTATION_COMPLETED,
                "Buổi tư vấn đã hoàn tất", ActivityVisibility.OWNER_ONLY);

        // Chủ thai kỳ thấy cả hai.
        mockMvc.perform(get("/api/v1/activity-feed")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));

        // Partner chỉ thấy dòng FAMILY.
        mockMvc.perform(get("/api/v1/activity-feed")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("FAMILY_TASK_ASSIGNED"));

        // Người ngoài nhóm không thấy gì.
        mockMvc.perform(get("/api/v1/activity-feed")
                        .header("Authorization", "Bearer " + outsider.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void removingTheScopeHidesTheFeedOnTheVeryNextCall() throws Exception {
        Session owner = registerViaOtp("0915000011", "Revoke Owner");
        Session partner = registerViaOtp("0915000012", "Revoke Partner");
        createPregnancyAndGroup(owner);
        String memberId = inviteAndAccept(owner, partner, Set.of("FAMILY_TASKS", "ACTIVITY_FEED"));
        createTaskAssignedTo(owner, memberId);

        mockMvc.perform(get("/api/v1/activity-feed")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        mockMvc.perform(patch("/api/v1/family-members/{id}", memberId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"FAMILY_TASKS\"],\"version\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/activity-feed")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void assigningATaskNotifiesTheAssigneeButNotTheAssigner() throws Exception {
        Session owner = registerViaOtp("0915000021", "Assign Owner");
        Session partner = registerViaOtp("0915000022", "Assign Partner");
        createPregnancyAndGroup(owner);
        String memberId = inviteAndAccept(owner, partner, Set.of("FAMILY_TASKS"));
        // Chấp nhận lời mời đã báo cho chủ nhóm một lần rồi; dọn sạch để đoạn dưới đếm đúng
        // những thông báo do CHÍNH thao tác giao việc sinh ra.
        markAllRead(owner);

        createTaskAssignedTo(owner, memberId);

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("FAMILY"))
                .andExpect(jsonPath("$.data.items[0].title").value("Bạn được giao một việc mới"));

        // Người giao việc không tự nhận thông báo về thao tác của chính mình.
        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void partnerDashboardShowsTheSharedActivityBlock() throws Exception {
        Session owner = registerViaOtp("0915000031", "Dash Owner");
        Session partner = registerViaOtp("0915000032", "Dash Partner");
        createPregnancyAndGroup(owner);
        String memberId = inviteAndAccept(
                owner, partner, Set.of("FAMILY_TASKS", "ACTIVITY_FEED", "PREGNANCY_SUMMARY"));
        createTaskAssignedTo(owner, memberId);

        mockMvc.perform(get("/api/v1/dashboard/partner")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activity_feed.length()").value(1))
                .andExpect(jsonPath("$.data.activity_feed[0].type").value("FAMILY_TASK_ASSIGNED"));
    }

    @Test
    void reassigningATaskNotifiesTheNewAssigneeOnly() throws Exception {
        Session owner = registerViaOtp("0915000041", "Reassign Owner");
        Session first = registerViaOtp("0915000042", "Reassign First");
        Session second = registerViaOtp("0915000043", "Reassign Second");
        createPregnancyAndGroup(owner);
        String firstMember = inviteAndAccept(owner, first, Set.of("FAMILY_TASKS"));
        String secondMember = inviteAndAccept(owner, second, Set.of("FAMILY_TASKS"));

        String taskId = createTaskAssignedTo(owner, firstMember);
        markAllRead(first);

        mockMvc.perform(patch("/api/v1/family/tasks/{id}", taskId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee_id\":\"" + secondMember + "\",\"version\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Bạn được giao một việc mới"));

        // Người bị lấy việc không nhận thêm thông báo nào.
        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true")
                        .header("Authorization", "Bearer " + first.accessToken()))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void completingATaskNotifiesTheGroupOwnerAndLandsOnTheFamilyFeed() throws Exception {
        Session owner = registerViaOtp("0915000051", "Complete Owner");
        Session partner = registerViaOtp("0915000052", "Complete Partner");
        createPregnancyAndGroup(owner);
        String memberId = inviteAndAccept(owner, partner, Set.of("FAMILY_TASKS", "ACTIVITY_FEED"));
        String taskId = createTaskAssignedTo(owner, memberId);
        markAllRead(owner);

        mockMvc.perform(patch("/api/v1/family/tasks/{id}", taskId)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\",\"version\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("FAMILY"))
                .andExpect(jsonPath("$.data.items[0].title")
                        .value("Một việc trong nhóm đã hoàn thành"));

        // Việc hoàn thành là chuyện chung của nhóm nên cả partner cũng thấy trên feed.
        mockMvc.perform(get("/api/v1/activity-feed")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("FAMILY_TASK_COMPLETED"));
    }

    @Test
    void editingATaskWithoutChangingAssigneeOrStatusNotifiesNobody() throws Exception {
        Session owner = registerViaOtp("0915000061", "Quiet Owner");
        Session partner = registerViaOtp("0915000062", "Quiet Partner");
        createPregnancyAndGroup(owner);
        String memberId = inviteAndAccept(owner, partner, Set.of("FAMILY_TASKS"));
        String taskId = createTaskAssignedTo(owner, memberId);
        markAllRead(partner);

        mockMvc.perform(patch("/api/v1/family/tasks/{id}", taskId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Mua vitamin va sat\",\"assignee_id\":\""
                                + memberId + "\",\"version\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true")
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // ----- helpers -----

    private void markAllRead(Session session) throws Exception {
        mockMvc.perform(post("/api/v1/notifications/read-all")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());
    }

    private String createTaskAssignedTo(Session creator, String memberId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/family/tasks")
                        .header("Authorization", "Bearer " + creator.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Mua vitamin\",\"priority\":\"LOW\","
                                + "\"assignee_id\":\"" + memberId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/id").stringValue();
    }

    private String createPregnancyAndGroup(Session owner) throws Exception {
        LocalDate dueDate = LocalDate.now(ZoneOffset.UTC).plusDays(100);
        MvcResult pregnancy = mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PregnancyBody(dueDate))))
                .andExpect(status().isCreated())
                .andReturn();
        mockMvc.perform(post("/api/v1/family-groups")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
        return objectMapper.readTree(pregnancy.getResponse().getContentAsString())
                .at("/data/id").stringValue();
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
        MvcResult accepted = mockMvc.perform(post("/api/v1/family-invitations/accept")
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptBody(token))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(accepted.getResponse().getContentAsString())
                .at("/data/id").stringValue();
    }

    private record PregnancyBody(LocalDate estimatedDueDate) { }
    private record InvitationBody(
            String invitedPhone, String relationship, Set<String> scopes, int expiresInHours) { }
    private record AcceptBody(String token) { }
}
