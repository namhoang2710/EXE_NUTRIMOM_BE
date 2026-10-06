package vn.nutrimom.consultation.video;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.*;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.*;
import vn.nutrimom.consultation.domain.*;
import vn.nutrimom.consultation.repository.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConsultationVideoIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-03T02:58:00Z"); // 09:58 VN
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired ExpertProfileRepository experts;
    @Autowired AvailabilitySlotRepository slots;
    @Autowired ConsultationRequestRepository requests;
    @Autowired ConsultationReviewRepository reviews;
    @Autowired VideoSessionRepository sessions;
    @Autowired VideoProperties properties;
    @Autowired ConsultationVideoService video;
    @Autowired VideoSessionCleanup cleanup;
    @MockitoBean LiveKitGateway livekit;
    @MockitoBean Clock clock;
    private String patient, expert, id;
    @BeforeEach void setup() {
        reset(livekit);
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        properties.setEnabled(true); properties.setUrl("wss://test.livekit.cloud");
        properties.setApiKey("test-api-key"); properties.setApiSecret("test-livekit-secret-at-least-32-bytes");
        patient = account("Patient", UserRole.USER);
        expert = account("Expert", UserRole.EXPERT);
        ExpertProfileEntity profile = new ExpertProfileEntity();
        profile.setUserId(expert); profile.setFullName("Dr Expert"); profile.setSpecialty(Specialty.HEALTH);
        experts.saveAndFlush(profile);
        AvailabilitySlotEntity slot = new AvailabilitySlotEntity(); slot.setExpertUserId(expert);
        slot.setSlotDate(LocalDate.of(2026, 10, 3)); slot.setStartTime(LocalTime.of(10, 0));
        slot.setEndTime(LocalTime.of(10, 30)); slot.setStatus(SlotStatus.BOOKED);
        slots.saveAndFlush(slot);
        ConsultationRequestEntity request = new ConsultationRequestEntity();
        request.setUserId(patient); request.setExpertUserId(expert); request.setSpecialty(Specialty.HEALTH);
        request.setSlotId(slot.getId()); request.setAssignmentType(AssignmentType.DIRECT);
        request.setStatus(ConsultationStatus.PENDING_CONSULTATION); request.setNote("Private booking note");
        id = requests.saveAndFlush(request).getId();
    }
    @Test void anonymousAndUnrelatedAccountsCannotReadOrJoin() throws Exception {
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        String stranger = account("Stranger", UserRole.USER);
        mvc.perform(get(path()).with(actor(stranger, "USER"))).andExpect(status().isNotFound());
        mvc.perform(post(path() + "/join").with(actor(stranger, "ADMIN"))).andExpect(status().isNotFound());
        assertThat(sessions.count()).isZero(); verifyNoInteractions(livekit);
    }
    @Test void bothAssignedParticipantsUseOneOpaqueRoomAndNoSecretsAppearInInfo() throws Exception {
        when(livekit.participantToken(anyString(), anyString(), any())).thenReturn("scoped-token");
        when(livekit.encryptionKey(anyString())).thenReturn("room-key");
        mvc.perform(get(path()).with(actor(patient, "USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.can_join").value(true))
                .andExpect(jsonPath("$.data.opens_at").value("2026-10-03T02:55:00Z"))
                .andExpect(jsonPath("$.data.closes_at").value("2026-10-03T03:35:00Z"))
                .andExpect(jsonPath("$.data.participant_token").doesNotExist())
                .andExpect(jsonPath("$.data.encryption_key").doesNotExist());
        mvc.perform(post(path() + "/join").with(actor(patient, "USER")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.participant_token").value("scoped-token"));
        mvc.perform(post(path() + "/join").with(actor(expert, "EXPERT"))).andExpect(status().isOk());
        VideoSession session = sessions.findById(id).orElseThrow();
        assertThat(session.getRoomName()).startsWith("nm-").doesNotContain(patient, expert, "Patient");
        assertThat(sessions.count()).isEqualTo(1);
        verify(livekit).participantToken(session.getRoomName(), patient, NOW.plusSeconds(300));
        verify(livekit).participantToken(session.getRoomName(), expert, NOW.plusSeconds(300));
    }
    @Test void joinWindowIsEnforcedByServerAndTokenCannotOutliveClosingTime() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-03T02:54:59Z"));
        mvc.perform(post(path() + "/join").with(actor(patient, "USER"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VIDEO_NOT_OPEN"));
        verifyNoInteractions(livekit);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-03T03:34:00Z"));
        video.join(patient, id);
        verify(livekit).participantToken(anyString(), eq(patient), eq(Instant.parse("2026-10-03T03:35:00Z")));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-03T03:35:00Z"));
        mvc.perform(post(path() + "/join").with(actor(patient, "USER"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VIDEO_ENDED"));
    }
    @Test void unscheduledRequestsAndInactiveParticipantsCannotStartCalls() throws Exception {
        var request = requests.findById(id).orElseThrow(); request.setStatus(ConsultationStatus.PENDING_EXPERT);
        requests.saveAndFlush(request);
        mvc.perform(post(path() + "/join").with(actor(patient, "USER"))).andExpect(status().isConflict());
        request.setStatus(ConsultationStatus.PENDING_CONSULTATION); requests.saveAndFlush(request);
        var expertUser = users.findById(expert).orElseThrow(); expertUser.setStatus(UserStatus.DISABLED); users.saveAndFlush(expertUser);
        mvc.perform(post(path() + "/join").with(actor(patient, "USER"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(livekit);
    }
    @Test void cancellationKeepsTimeSnapshotAndDeniesFurtherEntry() throws Exception {
        video.join(patient, id);
        String slotId = requests.findById(id).orElseThrow().getSlotId();
        mvc.perform(post("/api/v1/consultation-requests/" + id + "/cancel").with(actor(patient, "USER")))
                .andExpect(status().isOk());
        assertThat(slots.existsById(slotId)).isFalse();
        assertThat(sessions.findById(id).orElseThrow().getEndedAt()).isEqualTo(NOW);
        mvc.perform(get(path()).with(actor(patient, "USER"))).andExpect(jsonPath("$.data.state").value("ENDED"))
                .andExpect(jsonPath("$.data.closes_at").value("2026-10-03T03:35:00Z"));
        mvc.perform(post(path() + "/join").with(actor(expert, "EXPERT"))).andExpect(status().isConflict());
    }
    @Test void onlyAssignedExpertCanCompleteAndParticipantLeftNeverCompletesBooking() throws Exception {
        video.join(patient, id);
        String room = sessions.findById(id).orElseThrow().getRoomName();
        when(livekit.verifyWebhook("left", "signed")).thenReturn(new LiveKitGateway.RoomEvent("participant_left", room, patient, NOW));
        video.webhook("left", "signed");
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(ConsultationStatus.PENDING_CONSULTATION);
        mvc.perform(post(path() + "/complete").with(actor(patient, "USER"))).andExpect(status().isForbidden());
        mvc.perform(post(path() + "/complete").with(actor(expert, "EXPERT"))).andExpect(status().isOk());
        assertThat(sessions.findById(id).orElseThrow().getEndedAt()).isNotNull();
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(ConsultationStatus.COMPLETED);
    }
    @Test void missingConfigurationAndProviderFailureDoNotLoseBooking() throws Exception {
        properties.setUrl("");
        mvc.perform(get(path()).with(actor(patient, "USER"))).andExpect(jsonPath("$.data.state").value("UNAVAILABLE"));
        mvc.perform(post(path() + "/join").with(actor(patient, "USER"))).andExpect(status().isServiceUnavailable());
        properties.setUrl("wss://test.livekit.cloud");
        doThrow(new BusinessException(ErrorCode.VIDEO_PROVIDER_UNAVAILABLE)).when(livekit).ensureRoom(anyString());
        mvc.perform(post(path() + "/join").with(actor(patient, "USER"))).andExpect(status().isServiceUnavailable());
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(ConsultationStatus.PENDING_CONSULTATION);
    }
    @Test void webhookReplaysAreHarmlessAndDoNotIncludeUnknownParticipants() throws Exception {
        video.join(patient, id);
        String room = sessions.findById(id).orElseThrow().getRoomName();
        String rawBody = "{ \"event\": \"participant_joined\", \"name\": \"Tư vấn\" }\n";
        when(livekit.verifyWebhook(rawBody, "signed")).thenReturn(new LiveKitGateway.RoomEvent("participant_joined", room, patient, NOW));
        for (int replay = 0; replay < 2; replay++)
            mvc.perform(post("/api/v1/consultation-video/webhook").contentType("application/webhook+json")
                    .characterEncoding("UTF-8").header("Authorization", "signed").content(rawBody))
                    .andExpect(status().isNoContent());
        verify(livekit, times(2)).verifyWebhook(rawBody, "signed");
        when(livekit.verifyWebhook(rawBody, null)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        mvc.perform(post("/api/v1/consultation-video/webhook").contentType("application/webhook+json").content(rawBody))
                .andExpect(status().isUnauthorized());
        var session = sessions.findById(id).orElseThrow();
        assertThat(session.getUserJoinedAt()).isEqualTo(NOW); assertThat(session.getExpertJoinedAt()).isNull();
        when(livekit.verifyWebhook("stranger", "signed")).thenReturn(new LiveKitGateway.RoomEvent("participant_joined", room, "stranger", NOW));
        video.webhook("stranger", "signed");
        assertThat(session.getExpertJoinedAt()).isNull();
    }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentArrivalsCreateOneRoomAndCleanupRetriesAfterProviderFailure() throws Exception {
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { barrier.await(); return video.join(patient, id); });
            var second = pool.submit(() -> { barrier.await(); return video.join(expert, id); });
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            var session = sessions.findById(id).orElseThrow();
            verify(livekit, times(2)).ensureRoom(session.getRoomName());
            verify(livekit).participantToken(eq(session.getRoomName()), eq(patient), any());
            verify(livekit).participantToken(eq(session.getRoomName()), eq(expert), any());
            doThrow(new BusinessException(ErrorCode.VIDEO_PROVIDER_UNAVAILABLE))
                    .when(livekit).closeRoom(anyString(), anyString(), anyString(), any());
            mvc.perform(post("/api/v1/consultation-requests/" + id + "/cancel").with(actor(patient, "USER")))
                    .andExpect(status().isOk());
            assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(ConsultationStatus.CANCELLED);
            assertThat(sessions.findById(id).orElseThrow().getCleanupAt()).isNull();
            doNothing().when(livekit).closeRoom(anyString(), anyString(), anyString(), any());
            cleanup.cleanup(id);
            assertThat(sessions.findById(id).orElseThrow().getCleanupAt()).isEqualTo(NOW);
            cleanup.cleanup(id);
            verify(livekit, times(2)).closeRoom(eq(session.getRoomName()), eq(patient), eq(expert), eq(NOW));
        } finally { pool.shutdownNow(); }
    }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiryCleanupCompletesBookingAndAllowsOneReview() throws Exception {
        video.join(patient, id);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-03T03:35:01Z"));
        cleanup.cleanup(id);
        assertThat(sessions.findById(id).orElseThrow().getCleanupAt()).isNotNull();
        var completed = requests.findById(id).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(ConsultationStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isEqualTo(OffsetDateTime.parse("2026-10-03T03:35:00Z"));
        cleanup.cleanup(id);
        verify(livekit).closeRoom(anyString(), eq(patient), eq(expert), eq(Instant.parse("2026-10-03T03:35:01Z")));

        mvc.perform(post("/api/v1/consultation-requests/{id}/review", id)
                        .with(actor(patient, "USER")).contentType("application/json")
                        .content("{\"rating\":5}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/consultation-requests/{id}/review", id)
                        .with(actor(patient, "USER")).contentType("application/json")
                        .content("{\"rating\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REVIEW_ALREADY_EXISTS"));
    }
    @AfterEach void cleanCommittedFixtures() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return;
        String slotId = requests.findById(id).map(ConsultationRequestEntity::getSlotId).orElse(null);
        reviews.findByRequestId(id).ifPresent(reviews::delete);
        sessions.deleteById(id); requests.deleteById(id);
        if (slotId != null) slots.deleteById(slotId);
        experts.deleteById(expert); users.deleteById(expert); users.deleteById(patient);
    }
    private String path() { return "/api/v1/consultation-requests/" + id + "/video"; }
    private String account(String name, UserRole role) {
        var user = new UserEntity(); user.setDisplayName(name); user.setStatus(UserStatus.ACTIVE); user.setRoles(Set.of(role));
        return users.saveAndFlush(user).getId();
    }
    private RequestPostProcessor actor(String id, String role) {
        return jwt().jwt(t -> t.subject(id).claim("roles", List.of(role))).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
