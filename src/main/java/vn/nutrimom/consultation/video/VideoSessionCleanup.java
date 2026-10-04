package vn.nutrimom.consultation.video;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

@Configuration
@EnableScheduling
public class VideoSessionCleanup {
    private static final Logger log = LoggerFactory.getLogger(VideoSessionCleanup.class);
    private final ConsultationRequestRepository requests;
    private final VideoSessionRepository sessions;
    private final UserRepository users;
    private final ExpertProfileRepository experts;
    private final VideoProperties properties;
    private final LiveKitGateway livekit;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public VideoSessionCleanup(ConsultationRequestRepository requests, VideoSessionRepository sessions,
            UserRepository users, ExpertProfileRepository experts, VideoProperties properties,
            LiveKitGateway livekit, Clock clock, PlatformTransactionManager manager) {
        this.requests = requests; this.sessions = sessions; this.users = users; this.experts = experts;
        this.properties = properties; this.livekit = livekit; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @TransactionalEventListener
    public void afterBookingEnded(ConsultationVideoService.VideoEnded event) { cleanup(event.requestId()); }
    @Scheduled(fixedDelayString = "${app.consultation.video.cleanup-interval-ms:15000}", initialDelay = 15000)
    public void sweep() {
        if (properties.ready()) for (String id : sessions.findUncleanRequestIds()) cleanup(id);
    }
    public void cleanup(String id) {
        if (!properties.ready()) return;
        try {
            transaction.executeWithoutResult(tx -> {
                var request = requests.findByIdForUpdate(id).orElse(null);
                var session = sessions.findById(id).orElse(null);
                if (request == null || session == null || session.getCleanupAt() != null) return;
                Instant now = clock.instant();
                boolean activePair = users.findById(request.getUserId()).filter(u -> u.getStatus() == UserStatus.ACTIVE).isPresent()
                        && users.findById(request.getExpertUserId()).filter(u -> u.getStatus() == UserStatus.ACTIVE
                            && u.getRoles().contains(UserRole.EXPERT)).isPresent()
                        && experts.findByUserIdAndStatus(request.getExpertUserId(), ExpertStatus.ACTIVE).isPresent();
                if (session.getEndedAt() == null && request.getStatus() == ConsultationStatus.PENDING_CONSULTATION
                        && now.isBefore(session.getClosesAt()) && activePair) return;
                session.end(now);
                // Revoke refreshed tokens too, not merely those issued before the booking ended.
                livekit.closeRoom(session.getRoomName(), request.getUserId(), request.getExpertUserId(), now);
                session.cleaned(now);
                sessions.save(session);
            });
        } catch (RuntimeException e) {
            // Persistent unclean rows are the retry queue. Never log bearer tokens or provider bodies.
            log.warn("Video room cleanup will retry for consultation {} ({})", id, e.getClass().getSimpleName());
        }
    }
}
