package vn.nutrimom.assistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.assistant.dto.AssistantDtos.*;
import vn.nutrimom.assistant.persistence.*;
import vn.nutrimom.assistant.service.*;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.care.domain.*;
import vn.nutrimom.care.repository.*;
import vn.nutrimom.consultation.domain.*;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.knowledge.domain.*;
import vn.nutrimom.knowledge.repository.ArticleBookmarkRepository;
import vn.nutrimom.knowledge.repository.KnowledgeArticleRepository;
import vn.nutrimom.medicalrecord.domain.*;
import vn.nutrimom.medicalrecord.repository.MedicalRecordRepository;
import vn.nutrimom.payment.domain.*;
import vn.nutrimom.payment.repository.UserSubscriptionRepository;
import vn.nutrimom.support.ApiIntegrationTestSupport;

@SpringBootTest(properties = {"app.assistant.daily-user-limit=2", "app.assistant.daily-global-limit=1000", "app.assistant.daily-token-budget=10000000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantIntegrationTest extends ApiIntegrationTestSupport {
    @MockitoBean AiProviderClient provider;
    @Autowired AssistantStore store;
    @Autowired AssistantService service;
    @Autowired AssistantConversationRepository conversations;
    @Autowired AssistantMessageRepository messages;
    @Autowired MedicalRecordRepository medicalRecords;
    @Autowired KnowledgeArticleRepository articles;
    @Autowired UserRepository users;
    @Autowired AssistantContextService contexts;
    @Autowired PreparationItemRepository preparation;
    @Autowired BirthPlanRepository birthPlans;
    @Autowired ConsultationRequestRepository consultations;
    @Autowired ArticleBookmarkRepository bookmarks;
    @Autowired UserSubscriptionRepository subscriptions;
    private static final AtomicInteger PHONE = new AtomicInteger(100);
    private Session account() throws Exception { return registerViaOtp("0932700" + PHONE.incrementAndGet(), "Assistant Fixture"); }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/status")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/assistant/context")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/assistant/conversations")).andExpect(status().isUnauthorized());
    }
    @Test
    void defaultSettingsLoadOwnDataAutomaticallyWhileCloudRemainsOff() throws Exception {
        var user = account();
        mockMvc.perform(get("/api/v1/assistant/preferences").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.data.cloud_consent").value(false)).andExpect(jsonPath("$.data.use_profile").value(true))
                .andExpect(jsonPath("$.data.use_pregnancy").value(true)).andExpect(jsonPath("$.data.use_medical_records").value(true));
        var result = send(user, create(user), UUID.randomUUID().toString(), "Tôi lưu kết quả khám ở đâu?");
        assertEquals("GUIDE", result.at("/assistant_message/mode").stringValue());
        assertEquals("NOT_READY", result.at("/assistant_message/fallback_reason").stringValue());
        assertEquals("/app/profile/records", result.at("/assistant_message/citations/0/href").stringValue());
        assertTrue(result.at("/assistant_message/context_used").toString().contains("MEDICAL_RECORDS"));
        verify(provider, never()).complete(any(), anyString(), anyList());
    }
    @Test
    void anotherAccountCannotReadSendOrDeleteConversation() throws Exception {
        var owner = account(); var other = account(); String id = create(owner);
        mockMvc.perform(get("/api/v1/assistant/conversations/" + id).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/assistant/conversations/" + id).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/assistant/conversations/" + id + "/messages").header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON).content(body("Hello", UUID.randomUUID().toString()))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/assistant/conversations").header("Authorization", bearer(other))).andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(get("/api/v1/assistant/conversations/" + id).header("Authorization", bearer(owner))).andExpect(status().isOk());
    }
    @Test
    void consentIsRequiredEvenWhenProviderIsReady() throws Exception {
        when(provider.ready()).thenReturn(true);
        var user = account();
        var result = send(user, create(user), UUID.randomUUID().toString(), "Hướng dẫn sử dụng web");
        assertEquals("CONSENT_REQUIRED", result.at("/assistant_message/fallback_reason").stringValue());
        verify(provider, never()).complete(any(), anyString(), anyList());
    }
    @Test
    void sameClientMessageIsReplayedWithoutSpendingQuotaAgain() throws Exception {
        enableProvider(); var user = account(); prefs(user, true, false, false, false, 0);
        String id = create(user), key = UUID.randomUUID().toString();
        var first = send(user, id, key, "Tôi lưu kết quả khám ở đâu?");
        var second = send(user, id, key, "Tôi lưu kết quả khám ở đâu?");
        assertEquals(first.at("/assistant_message/id").stringValue(), second.at("/assistant_message/id").stringValue());
        assertEquals(1, second.at("/remaining_ai_messages").intValue());
        assertEquals(2, messages.countByConversationId(id));
        verify(provider, times(1)).complete(any(), anyString(), anyList());
        mockMvc.perform(post("/api/v1/assistant/conversations/" + id + "/messages").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body("Different content", key))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }
    @Test
    void permissionChangesMakePreviousHistoryReadOnly() throws Exception {
        var user = account(); String old = create(user);
        send(user, old, UUID.randomUUID().toString(), "Hướng dẫn web");
        prefs(user, false, false, true, false, 0);
        mockMvc.perform(get("/api/v1/assistant/conversations/" + old).header("Authorization", bearer(user))).andExpect(jsonPath("$.data.conversation.read_only").value(true));
        mockMvc.perform(post("/api/v1/assistant/conversations/" + old + "/messages").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body("Tiếp tục", UUID.randomUUID().toString()))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        var response = send(user, create(user), UUID.randomUUID().toString(), "Thai kỳ hiện tại của tôi");
        assertTrue(response.at("/assistant_message/content").stringValue().contains("Chưa có thai kỳ"));
    }
    @Test
    void privateContextIsMinimizedAndOnlyPublishedArticlesAndOwnedRecordsAreRead() throws Exception {
        enableProvider(); var user = account(); var other = account();
        String pregnancy = pregnancy(user); String otherPregnancy = pregnancy(other);
        record(user, pregnancy, "Hồ sơ riêng của tôi", "own-visible");
        record(other, otherPregnancy, "Hồ sơ tài khoản khác", "other-private-secret");
        var removed = record(user, pregnancy, "Hồ sơ đã xóa", "deleted-secret");
        removed.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC)); medicalRecords.saveAndFlush(removed);
        String visibleTitle = "Dinh dưỡng published " + UUID.randomUUID();
        article(user, visibleTitle, ArticleStatus.published, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        article(user, "Dinh dưỡng draft-secret", ArticleStatus.draft, null);
        article(user, "Dinh dưỡng future-secret", ArticleStatus.published, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
        prefs(user, true, true, true, true, 0);
        doAnswer(call -> {
            AssistantContextService.Context context = call.getArgument(0);
            String snapshot = objectMapper.writeValueAsString(context);
            assertTrue(snapshot.contains("own-visible")); assertTrue(snapshot.contains(visibleTitle));
            assertFalse(snapshot.contains("other-private-secret")); assertFalse(snapshot.contains("deleted-secret"));
            assertFalse(snapshot.contains("draft-secret")); assertFalse(snapshot.contains("future-secret"));
            assertFalse(snapshot.contains(user.phone())); assertFalse(snapshot.contains(user.userId())); assertFalse(snapshot.contains("Assistant Fixture"));
            assertTrue(context.facts().containsKey("pregnancy"));
            return new AiProviderClient.Completion("Tóm tắt theo hồ sơ đã lưu.", List.of("pregnancy:current"), List.of(), false);
        }).when(provider).complete(any(), anyString(), anyList());
        var result = send(user, create(user), UUID.randomUUID().toString(), "Tóm tắt hồ sơ và bài viết dinh dưỡng");
        assertEquals("AI", result.at("/assistant_message/mode").stringValue());
        assertTrue(result.at("/assistant_message/context_used").toString().contains("MEDICAL_RECORDS"));
    }
    @Test
    void quotaSurvivesDeletingConversationAndReturnsAvailableGuidance() throws Exception {
        enableProvider(); var user = account(); prefs(user, true, false, false, false, 0);
        String first = create(user);
        send(user, first, UUID.randomUUID().toString(), "Tôi lưu kết quả khám ở đâu?");
        mockMvc.perform(delete("/api/v1/assistant/conversations/" + first).header("Authorization", bearer(user))).andExpect(status().isNoContent());
        String second = create(user);
        send(user, second, UUID.randomUUID().toString(), "Tôi lưu kết quả khám ở đâu?");
        var limited = send(user, second, UUID.randomUUID().toString(), "Tôi lưu kết quả khám ở đâu?");
        assertEquals("DAILY_LIMIT", limited.at("/assistant_message/fallback_reason").stringValue());
        assertEquals(0, limited.at("/remaining_ai_messages").intValue());
        verify(provider, times(2)).complete(any(), anyString(), anyList());
    }
    @Test
    void providerLimitAndInventedSourcesFallBackWithoutReturningUnverifiedAnswer() throws Exception {
        enableProvider(); var user = account(); prefs(user, true, false, false, false, 0); String id = create(user);
        doThrow(new AiProviderClient.ProviderFailure("PROVIDER_LIMIT")).when(provider).complete(any(), anyString(), anyList());
        assertEquals("PROVIDER_LIMIT", send(user, id, UUID.randomUUID().toString(), "Hướng dẫn web").at("/assistant_message/fallback_reason").stringValue());
        doReturn(new AiProviderClient.Completion("invented-answer", List.of("other-record"), List.of(), false)).when(provider).complete(any(), anyString(), anyList());
        var response = send(user, id, UUID.randomUUID().toString(), "Hướng dẫn web");
        assertEquals("INVALID_RESPONSE", response.at("/assistant_message/fallback_reason").stringValue());
        assertFalse(response.at("/assistant_message/content").stringValue().contains("invented-answer"));
    }
    @Test
    void changingPermissionWhileProviderIsWorkingDiscardsItsAnswer() throws Exception {
        enableProvider(); var user = account(); prefs(user, true, false, false, false, 0); String id = create(user);
        doAnswer(call -> {
            store.updatePreferences(user.userId(), new UpdatePreferences(false, false, false, false, 1L));
            return new AiProviderClient.Completion("private-output-must-be-discarded", List.of("guide:profile"), List.of(), false);
        }).when(provider).complete(any(), anyString(), anyList());
        mockMvc.perform(post("/api/v1/assistant/conversations/" + id + "/messages").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body("Hướng dẫn web", UUID.randomUUID().toString()))).andExpect(status().isConflict());
        assertEquals(1, messages.countByConversationId(id));
        assertNull(conversations.findById(id).orElseThrow().processingMessageId);
        mockMvc.perform(get("/api/v1/assistant/conversations/" + id).header("Authorization", bearer(user))).andExpect(jsonPath("$.data.conversation.read_only").value(true));
    }
    @Test
    void concurrentMessagesAcrossConversationsCannotCallProviderTwice() throws Exception {
        enableProvider(); var user = account(); prefs(user, true, false, false, false, 0); String first = create(user), second = create(user);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
            AssistantContextService.Context context = call.getArgument(0);
            return new AiProviderClient.Completion("Đọc hướng dẫn.", List.of(context.evidence().get(0).source().id()), List.of(), false);
        }).when(provider).complete(any(), anyString(), anyList());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> pending = executor.submit(() -> service.send(user.userId(), first, new SendMessage("Hướng dẫn web", UUID.randomUUID().toString(), "/app")));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            mockMvc.perform(post("/api/v1/assistant/conversations/" + second + "/messages").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body("Hướng dẫn web", UUID.randomUUID().toString()))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSISTANT_BUSY"));
            release.countDown(); assertEquals("AI", pending.get(10, TimeUnit.SECONDS).assistantMessage().mode());
            verify(provider, times(1)).complete(any(), anyString(), anyList());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test
    void medicalSafetyAndCrossAccountRequestsDoNotCallProvider() throws Exception {
        enableProvider(); var user = account(); prefs(user, true, false, false, false, 0); String id = create(user);
        assertTrue(send(user, id, UUID.randomUUID().toString(), "Tôi đang khó thở").at("/assistant_message/emergency_detected").booleanValue());
        assertEquals("SAFETY", send(user, id, UUID.randomUUID().toString(), "Tăng liều thuốc cho tôi").at("/assistant_message/mode").stringValue());
        assertEquals("GUIDE", send(user, id, UUID.randomUUID().toString(), "Xem thông tin tài khoản khác").at("/assistant_message/mode").stringValue());
        verify(provider, never()).complete(any(), anyString(), anyList());
    }
    @Test
    void deletionAndRetentionRemoveStoredMessages() throws Exception {
        var user = account(); String id = create(user); send(user, id, UUID.randomUUID().toString(), "Hướng dẫn web");
        var conversation = conversations.findById(id).orElseThrow(); conversation.updatedAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(40); conversations.saveAndFlush(conversation);
        mockMvc.perform(get("/api/v1/assistant/conversations/" + id).header("Authorization", bearer(user))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/assistant/conversations").header("Authorization", bearer(user))).andExpect(jsonPath("$.data").isEmpty());
        assertEquals(0, messages.countByConversationId(id)); assertFalse(conversations.existsById(id));
        String scheduled = create(user); send(user, scheduled, UUID.randomUUID().toString(), "Hướng dẫn web");
        var expired = conversations.findById(scheduled).orElseThrow(); expired.updatedAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(40); conversations.saveAndFlush(expired);
        store.purgeExpired();
        assertEquals(0, messages.countByConversationId(scheduled)); assertFalse(conversations.existsById(scheduled));
    }
    @Test
    void blankOversizedMessagesAndInvalidRequestIdsAreRejected() throws Exception {
        var user = account(); String id = create(user);
        for (String content : List.of(" ", "a".repeat(2001))) mockMvc.perform(post("/api/v1/assistant/conversations/" + id + "/messages").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body(content, UUID.randomUUID().toString()))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/assistant/conversations/" + id + "/messages").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body("Hello", "not-a-uuid"))).andExpect(status().isUnprocessableEntity());
    }
    @Test
    void historyLimitReservesSpaceForAnswerButAllowsRetryOfPendingQuestion() throws Exception {
        var user = account(); String id = create(user);
        String pendingKey = null;
        for (int index = 0; index < 59; index++) {
            var message = new AssistantMessage(); message.id = UUID.randomUUID().toString(); message.conversationId = id;
            message.clientMessageId = UUID.randomUUID().toString(); message.role = "USER"; message.content = "Hướng dẫn web";
            message.createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(59 - index);
            messages.saveAndFlush(message); pendingKey = message.clientMessageId;
        }
        mockMvc.perform(post("/api/v1/assistant/conversations/" + id + "/messages").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body("Hướng dẫn web", UUID.randomUUID().toString())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSISTANT_HISTORY_LIMIT"));
        assertEquals(59, messages.countByConversationId(id));
        send(user, id, pendingKey, "Hướng dẫn web");
        assertEquals(60, messages.countByConversationId(id));
    }
    @Test
    void loginOverviewReadsCurrentOwnedDataWithoutCreatingCareRecords() throws Exception {
        var owner = account(); var other = account(); String pregnancy = pregnancy(owner);
        record(owner, pregnancy, "Hồ sơ của tôi", "owned");
        record(other, pregnancy(other), "Hồ sơ khác", "other-private");
        mockMvc.perform(get("/api/v1/assistant/context").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.display_name").value("Assistant Fixture"))
                .andExpect(jsonPath("$.data.context_version").value(0));
        var context = contexts.build(owner.userId(), store.preferences(owner.userId()), "Tóm tắt thông tin của tôi", "/app");
        assertEquals(1L, ((Map<?, ?>) context.facts().get("medical_records")).get("total"));
        assertEquals(0, ((Map<?, ?>) context.facts().get("care_plan")).get("checklist_total"));
        assertTrue(preparation.findByPregnancyIdOrderBySortOrderAsc(pregnancy).isEmpty());
        assertTrue(birthPlans.findByPregnancyId(pregnancy).isEmpty());
        record(owner, pregnancy, "Hồ sơ vừa thêm", "fresh-visible");
        context = contexts.build(owner.userId(), store.preferences(owner.userId()), "Tóm tắt thông tin của tôi", "/app");
        assertEquals(2L, ((Map<?, ?>) context.facts().get("medical_records")).get("total"));
        assertFalse(objectMapper.writeValueAsString(context).contains("other-private"));
    }
    @Test
    void activityAndCareContextExcludeOtherAccountsAndUnpublishedBookmarks() throws Exception {
        var owner = account(); var other = account(); String pregnancy = pregnancy(owner); String otherPregnancy = pregnancy(other);
        preparation(owner, pregnancy, "Chuẩn bị hồ sơ nhập viện");
        preparation(other, otherPregnancy, "other-care-secret");
        var plan = new BirthPlanEntity(); plan.setPregnancyId(pregnancy); plan.setFreeTextNote("own-birth-plan");
        plan.setCompanion("private-companion-secret"); birthPlans.saveAndFlush(plan);
        consultations(owner, ConsultationStatus.PENDING_EXPERT); consultations(other, ConsultationStatus.COMPLETED);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        subscriptions.saveAndFlush(new UserSubscriptionEntity(owner.userId(), PlanTier.PLAN_99K, now.minusDays(1), now.plusDays(20)));
        subscriptions.saveAndFlush(new UserSubscriptionEntity(other.userId(), PlanTier.PLAN_399K, now.minusDays(1), now.plusDays(20)));
        bookmark(owner, article(owner, "own-saved-visible", ArticleStatus.published, now.minusDays(1)));
        bookmark(owner, article(owner, "saved-draft-secret", ArticleStatus.draft, null));
        bookmark(owner, article(owner, "saved-future-secret", ArticleStatus.published, now.plusDays(1)));
        bookmark(other, article(other, "other-bookmark-secret", ArticleStatus.published, now.minusDays(1)));
        var context = contexts.build(owner.userId(), store.preferences(owner.userId()), "Các bài đã lưu và lịch tư vấn của tôi", "/app");
        assertEquals(1L, ((Map<?, ?>) context.facts().get("consultations")).get("total"));
        assertEquals(1L, ((Map<?, ?>) context.facts().get("saved_articles")).get("total"));
        assertEquals(PlanTier.PLAN_99K.getDisplayName(), ((Map<?, ?>) context.facts().get("subscription")).get("plan_name"));
        assertEquals(1, ((Map<?, ?>) context.facts().get("care_plan")).get("checklist_total"));
        String snapshot = objectMapper.writeValueAsString(context);
        assertTrue(snapshot.contains("own-birth-plan")); assertTrue(snapshot.contains("own-saved-visible"));
        for (String secret : List.of("other-care-secret", "private-companion-secret", "saved-draft-secret", "saved-future-secret", "other-bookmark-secret")) assertFalse(snapshot.contains(secret), secret);
        var reply = send(owner, create(owner), UUID.randomUUID().toString(), "Lịch tư vấn của tôi ở đâu?");
        String text = reply.at("/assistant_message/content").stringValue();
        assertTrue(text.contains("Sản khoa — Đang chờ chuyên gia")); assertFalse(text.contains("specialty="));
    }
    @Test
    void personalOverviewAndIdentityWorkLocallyAndIdentityNeverEntersModelHistory() throws Exception {
        enableProvider(); var owner = account(); pregnancy(owner); String id = create(owner);
        var overview = send(owner, id, UUID.randomUUID().toString(), "Bạn biết gì về thông tin của tôi?");
        assertEquals("GUIDE", overview.at("/assistant_message/mode").stringValue());
        assertTrue(overview.at("/assistant_message/content").stringValue().contains("Thai kỳ: tuần"));
        var identity = send(owner, id, UUID.randomUUID().toString(), "Tên của tôi là gì?");
        assertTrue(identity.at("/assistant_message/content").stringValue().contains("Assistant Fixture"));
        assertTrue(send(owner, id, UUID.randomUUID().toString(), "Số điện thoại của tôi là gì?").at("/assistant_message/content").stringValue().contains(owner.phone().substring(1)));
        verify(provider, never()).complete(any(), anyString(), anyList());
        prefs(owner, true, true, true, true, 0); id = create(owner);
        send(owner, id, UUID.randomUUID().toString(), "Tên của tôi là gì?");
        doAnswer(call -> {
            String payload = objectMapper.writeValueAsString(List.of(call.getArgument(0), call.getArgument(2)));
            assertFalse(payload.contains("Assistant Fixture")); assertFalse(payload.contains(owner.phone()));
            return new AiProviderClient.Completion("Xem hướng dẫn web.", List.of("guide:start"), List.of(), false);
        }).when(provider).complete(any(), anyString(), anyList());
        assertEquals("AI", send(owner, id, UUID.randomUUID().toString(), "Hướng dẫn sử dụng web").at("/assistant_message/mode").stringValue());
    }
    @Test
    void explicitOptOutRemovesAllPrivateContextAndDoesNotRevealIdentity() throws Exception {
        var owner = account(); pregnancy(owner); prefs(owner, false, false, false, false, 0);
        var overview = data(mockMvc.perform(get("/api/v1/assistant/context").header("Authorization", bearer(owner))).andExpect(status().isOk()).andReturn());
        assertEquals(0, overview.at("/items").size()); assertTrue(overview.at("/display_name").isNull() || overview.at("/display_name").isMissingNode());
        var context = contexts.build(owner.userId(), store.preferences(owner.userId()), "Tóm tắt thông tin của tôi", "/app");
        assertEquals(Set.of("website_knowledge_version"), context.facts().keySet());
        var reply = send(owner, create(owner), UUID.randomUUID().toString(), "Tên của tôi là gì?");
        assertFalse(reply.at("/assistant_message/content").stringValue().contains("Assistant Fixture"));
    }
    @Test
    void questionsCanRetrieveOlderMatchingRecordsAndFollowUpKeepsTheWebsiteTopic() throws Exception {
        var owner = account(); String pregnancy = pregnancy(owner);
        var old = record(owner, pregnancy, "Siêu âm Doppler cũ", "old-matching-result");
        old.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC).minusYears(1)); medicalRecords.saveAndFlush(old);
        for (int index = 0; index < 4; index++) record(owner, pregnancy, "Khám thông thường " + index, "new-unrelated");
        var context = contexts.build(owner.userId(), store.preferences(owner.userId()), "Kết quả Doppler của tôi", "/app");
        assertTrue(objectMapper.writeValueAsString(context).contains("old-matching-result"));
        assertFalse(objectMapper.writeValueAsString(context).contains("new-unrelated"));
        var unaccented = contexts.build(owner.userId(), store.preferences(owner.userId()), "Ket qua sieu am cua toi", "/app");
        assertTrue(objectMapper.writeValueAsString(unaccented).contains("old-matching-result"));
        assertFalse(objectMapper.writeValueAsString(unaccented).contains("new-unrelated"));
        String id = create(owner);
        send(owner, id, UUID.randomUUID().toString(), "Tôi hủy lịch tư vấn như thế nào?");
        var followUp = send(owner, id, UUID.randomUUID().toString(), "Hướng dẫn tiếp chi tiết hơn");
        assertEquals("/app/consultations/history", followUp.at("/assistant_message/citations/1/href").stringValue());
    }
    @Test
    void nutritionRetrievalPrioritizesCurrentStageOverNewerUnrelatedContent() throws Exception {
        var owner = account(); pregnancy(owner);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var relevant = article(owner, "Bữa ăn theo thai kỳ — phù hợp", ArticleStatus.published, now.minusMonths(2));
        relevant.setStage("trimester-2"); articles.saveAndFlush(relevant);
        for (int index = 0; index < 4; index++) {
            var newer = article(owner, "Vận động — không phải dinh dưỡng " + index, ArticleStatus.published, now.minusMinutes(index + 1));
            newer.setCategory("exercise"); newer.setStage("trimester-2"); articles.saveAndFlush(newer);
        }
        var context = contexts.build(owner.userId(), store.preferences(owner.userId()), "Gợi ý bài viết dinh dưỡng phù hợp với thai kỳ của tôi", "/app");
        var sources = context.evidence().stream().filter(e -> e.source().type().equals("ARTICLE")).toList();
        assertFalse(sources.isEmpty()); assertEquals(relevant.getTitle(), sources.get(0).source().title());
        assertTrue(sources.stream().noneMatch(e -> e.source().title().contains("Vận động")));
    }
    private void enableProvider() {
        when(provider.ready()).thenReturn(true);
        when(provider.complete(any(), anyString(), anyList())).thenAnswer(call -> {
            AssistantContextService.Context context = call.getArgument(0);
            return new AiProviderClient.Completion("Hướng dẫn từ NutriMom.", List.of(context.evidence().get(0).source().id()), List.of(), false);
        });
    }
    private String create(Session user) throws Exception {
        return data(mockMvc.perform(post("/api/v1/assistant/conversations").header("Authorization", bearer(user))).andExpect(status().isCreated()).andReturn()).at("/id").stringValue();
    }
    private JsonNode send(Session user, String id, String key, String content) throws Exception {
        return data(mockMvc.perform(post("/api/v1/assistant/conversations/" + id + "/messages").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body(content, key))).andExpect(status().isOk()).andReturn());
    }
    private void prefs(Session user, boolean cloud, boolean profile, boolean pregnancy, boolean records, long version) throws Exception {
        mockMvc.perform(patch("/api/v1/assistant/preferences").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("cloud_consent", cloud, "use_profile", profile, "use_pregnancy", pregnancy, "use_medical_records", records, "version", version)))).andExpect(status().isOk());
    }
    private String pregnancy(Session user) throws Exception {
        return data(mockMvc.perform(post("/api/v1/pregnancies").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("estimated_due_date", LocalDate.now(ZoneOffset.UTC).plusDays(100).toString(), "timezone", "Asia/Ho_Chi_Minh")))).andExpect(status().isCreated()).andReturn()).at("/id").stringValue();
    }
    private MedicalRecordEntity record(Session user, String pregnancy, String title, String summary) {
        var record = new MedicalRecordEntity(); record.setOwnerUserId(user.userId()); record.setPregnancyId(pregnancy);
        record.setTitle(title); record.setCategory(MedicalRecordCategory.OTHER); record.setSummary(summary); record.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
        return medicalRecords.saveAndFlush(record);
    }
    private KnowledgeArticle article(Session user, String title, ArticleStatus status, OffsetDateTime publishedAt) {
        var article = new KnowledgeArticle(); article.setAuthor(users.findById(user.userId()).orElseThrow());
        article.setSlug("fixture-" + UUID.randomUUID()); article.setTitle(title); article.setExcerpt("Nội dung fixture cho kiểm thử tìm nguồn.");
        article.setCategory("nutrition"); article.setStage("pregnancy"); article.setStatus(status); article.setPublishedAt(publishedAt); return articles.saveAndFlush(article);
    }
    private void preparation(Session owner, String pregnancy, String title) {
        var item = new PreparationItemEntity(); item.setPregnancyId(pregnancy); item.setGroupCode("DOCUMENTS"); item.setTitle(title); preparation.saveAndFlush(item);
    }
    private void consultations(Session owner, ConsultationStatus status) {
        var request = new ConsultationRequestEntity(); request.setUserId(owner.userId()); request.setSpecialty(Specialty.OBSTETRICS);
        request.setAssignmentType(AssignmentType.RANDOM); request.setStatus(status); consultations.saveAndFlush(request);
    }
    private void bookmark(Session owner, KnowledgeArticle article) {
        var bookmark = new ArticleBookmark(); bookmark.setUser(users.findById(owner.userId()).orElseThrow()); bookmark.setArticle(article); bookmarks.saveAndFlush(bookmark);
    }
    private String body(String content, String key) { return objectMapper.writeValueAsString(Map.of("content", content, "client_message_id", key, "page_path", "/app")); }
    private JsonNode data(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data"); }
    private String bearer(Session user) { return "Bearer " + user.accessToken(); }
}
