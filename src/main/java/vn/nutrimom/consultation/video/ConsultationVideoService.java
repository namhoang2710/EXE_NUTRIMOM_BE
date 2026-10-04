package vn.nutrimom.consultation.video;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.repository.*;
import vn.nutrimom.consultation.video.VideoDtos.*;

@Service
public class ConsultationVideoService {
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ConsultationRequestRepository requests;
    private final AvailabilitySlotRepository slots;
    private final ExpertProfileRepository experts;
    private final UserRepository users;
    private final VideoSessionRepository sessions;
    private final VideoProperties properties;
    private final LiveKitGateway livekit;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    public record VideoEnded(String requestId) { }
    public ConsultationVideoService(ConsultationRequestRepository requests, AvailabilitySlotRepository slots,
            ExpertProfileRepository experts, UserRepository users, VideoSessionRepository sessions,
            VideoProperties properties, LiveKitGateway livekit, Clock clock, ApplicationEventPublisher events) {
        this.requests = requests; this.slots = slots; this.experts = experts; this.users = users;
        this.sessions = sessions; this.properties = properties; this.livekit = livekit; this.clock = clock; this.events = events;
    }
    @Transactional(readOnly = true)
    public RoomInfo info(String actor, String id) {
        var request = requests.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        boolean expert = authorize(actor, request);
        var session = sessions.findById(id).orElse(null);
        var window = window(request, session);
        Instant now = clock.instant();
        String state = state(request, session, window, now);
        return new RoomInfo(id, users.findById(request.getUserId()).map(u -> u.getDisplayName()).orElse("Người dùng"),
                request.getExpertUserId() == null ? null : experts.findById(request.getExpertUserId()).map(e -> e.getFullName()).orElse(null),
                request.getSpecialty(), request.getNote(), expert, request.getStatus(), state,
                "READY".equals(state), properties.ready(), window[0], window[1], now);
    }
    @Transactional
    public JoinRoom join(String actor, String id) {
        // All mutations (join, cancellation, completion, cleanup) lock the booking first.
        var request = requests.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        authorize(actor, request);
        var session = sessions.findById(id).orElse(null);
        var window = window(request, session);
        Instant now = clock.instant();
        String state = state(request, session, window, now);
        if ("UNAVAILABLE".equals(state)) throw new BusinessException(ErrorCode.VIDEO_NOT_CONFIGURED);
        if ("ENDED".equals(state)) throw new BusinessException(ErrorCode.VIDEO_ENDED);
        if (!"READY".equals(state)) throw new BusinessException(ErrorCode.VIDEO_NOT_OPEN);
        requireActivePair(request);
        if (session == null) session = sessions.saveAndFlush(new VideoSession(id,
                "nm-" + UUID.randomUUID(), window[0], window[1]));
        livekit.ensureRoom(session.getRoomName());
        Instant expiry = now.plus(properties.getTokenTtl());
        if (expiry.isAfter(window[1])) expiry = window[1];
        return new JoinRoom(properties.getUrl(), livekit.participantToken(session.getRoomName(), actor, expiry),
                livekit.encryptionKey(session.getRoomName()), window[1]);
    }
    /** Called inside the booking transaction; provider failures never undo cancellation/completion. */
    @Transactional
    public void stop(String id) {
        sessions.findById(id).ifPresent(s -> {
            s.end(clock.instant()); sessions.save(s); events.publishEvent(new VideoEnded(id));
        });
    }
    @Transactional
    public void webhook(String body, String authorization) {
        var event = livekit.verifyWebhook(body, authorization);
        if (!"participant_joined".equals(event.type())) return;
        // Only fetch the ID before locking, so the session is read after concurrent cleanup commits.
        var requestId = sessions.findRequestIdByRoomName(event.room()).orElse(null);
        if (requestId == null) return;
        var request = requests.findByIdForUpdate(requestId).orElse(null);
        if (request == null) return;
        var session = sessions.findById(request.getId()).orElseThrow();
        if (session.getEndedAt() != null || event.createdAt().isBefore(session.getOpensAt())
                || !event.createdAt().isBefore(session.getClosesAt()) || event.createdAt().isAfter(clock.instant().plusSeconds(30))) return;
        if (request.getUserId().equals(event.identity())) session.markJoined(false, event.createdAt());
        else if (request.getExpertUserId().equals(event.identity())) session.markJoined(true, event.createdAt());
        // Replayed join notifications are harmless; leaving never fulfils a booking.
        sessions.save(session);
    }
    private boolean authorize(String actor, ConsultationRequestEntity request) {
        var account = users.findById(actor).filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE));
        if (actor.equals(request.getUserId())) return false;
        if (actor.equals(request.getExpertUserId()) && account.getRoles().contains(UserRole.EXPERT)
                && experts.findByUserIdAndStatus(actor, ExpertStatus.ACTIVE).isPresent()) return true;
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    private void requireActivePair(ConsultationRequestEntity request) {
        boolean patientActive = users.findById(request.getUserId()).filter(u -> u.getStatus() == UserStatus.ACTIVE).isPresent();
        boolean expertActive = users.findById(request.getExpertUserId()).filter(u -> u.getStatus() == UserStatus.ACTIVE
                && u.getRoles().contains(UserRole.EXPERT)).isPresent()
                && experts.findByUserIdAndStatus(request.getExpertUserId(), ExpertStatus.ACTIVE).isPresent();
        if (!patientActive || !expertActive) throw new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE);
    }
    private Instant[] window(ConsultationRequestEntity request, VideoSession session) {
        if (session != null) return new Instant[]{session.getOpensAt(), session.getClosesAt()};
        if (request.getSlotId() == null) return new Instant[]{null, null};
        return slots.findById(request.getSlotId()).map(s -> new Instant[]{
                s.getSlotDate().atTime(s.getStartTime()).atZone(VN).toInstant().minus(properties.getJoinEarly()),
                s.getSlotDate().atTime(s.getEndTime()).atZone(VN).toInstant().plus(properties.getEndGrace())})
                .orElse(new Instant[]{null, null});
    }
    private String state(ConsultationRequestEntity request, VideoSession session, Instant[] window, Instant now) {
        if (request.getStatus() == ConsultationStatus.CANCELLED || request.getStatus() == ConsultationStatus.COMPLETED
                || (session != null && session.getEndedAt() != null) || (window[1] != null && !now.isBefore(window[1]))) return "ENDED";
        if (request.getStatus() != ConsultationStatus.PENDING_CONSULTATION || window[0] == null) return "UNSCHEDULED";
        if (!properties.ready()) return "UNAVAILABLE";
        return now.isBefore(window[0]) ? "SCHEDULED" : "READY";
    }
}
