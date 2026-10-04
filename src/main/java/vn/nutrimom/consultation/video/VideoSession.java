package vn.nutrimom.consultation.video;

import jakarta.persistence.*;
import java.time.Instant;

/** Room state is separate from consultation fulfilment. No access tokens or health data are stored. */
@Entity
@Table(name = "consultation_video_sessions", schema = "app")
public class VideoSession {
    @Id @Column(name = "request_id", length = 36) private String requestId;
    @Column(name = "room_name", nullable = false, unique = true, length = 80) private String roomName;
    @Column(name = "opens_at", nullable = false) private Instant opensAt;
    @Column(name = "closes_at", nullable = false) private Instant closesAt;
    @Column(name = "user_joined_at") private Instant userJoinedAt;
    @Column(name = "expert_joined_at") private Instant expertJoinedAt;
    @Column(name = "ended_at") private Instant endedAt;
    @Column(name = "cleanup_at") private Instant cleanupAt;
    protected VideoSession() { }
    public VideoSession(String requestId, String roomName, Instant opensAt, Instant closesAt) {
        this.requestId = requestId; this.roomName = roomName;
        this.opensAt = opensAt; this.closesAt = closesAt;
    }
    public String getRequestId() { return requestId; }
    public String getRoomName() { return roomName; }
    public Instant getOpensAt() { return opensAt; }
    public Instant getClosesAt() { return closesAt; }
    public Instant getUserJoinedAt() { return userJoinedAt; }
    public Instant getExpertJoinedAt() { return expertJoinedAt; }
    public Instant getEndedAt() { return endedAt; }
    public Instant getCleanupAt() { return cleanupAt; }
    public void markJoined(boolean expert, Instant at) {
        if (expert && expertJoinedAt == null) expertJoinedAt = at;
        if (!expert && userJoinedAt == null) userJoinedAt = at;
    }
    public void end(Instant at) { if (endedAt == null) endedAt = at; }
    public void cleaned(Instant at) { cleanupAt = at; }
}
