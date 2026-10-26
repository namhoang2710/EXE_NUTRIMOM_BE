package vn.nutrimom.analytics.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "traffic_logs", schema = "app")
public class TrafficLogEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 50)
    private TrafficSource source;

    @Column(name = "medium", length = 50)
    private String medium;

    @Column(name = "campaign", length = 100)
    private String campaign;

    @Column(name = "landing_path", length = 255)
    private String landingPath;

    @Column(name = "referrer", length = 500)
    private String referrer;

    @Column(name = "visitor_session_id", length = 100)
    private String visitorSessionId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public TrafficLogEntity() {
    }

    public TrafficLogEntity(TrafficSource source, String medium, String campaign, String landingPath,
                            String referrer, String visitorSessionId, String userId, String ipHash, String userAgent) {
        this.id = UUID.randomUUID().toString();
        this.source = source != null ? source : TrafficSource.DIRECT;
        this.medium = medium;
        this.campaign = campaign;
        this.landingPath = landingPath;
        this.referrer = referrer;
        this.visitorSessionId = visitorSessionId;
        this.userId = userId;
        this.ipHash = ipHash;
        this.userAgent = userAgent;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public TrafficSource getSource() { return source; }
    public void setSource(TrafficSource source) { this.source = source; }
    public String getMedium() { return medium; }
    public void setMedium(String medium) { this.medium = medium; }
    public String getCampaign() { return campaign; }
    public void setCampaign(String campaign) { this.campaign = campaign; }
    public String getLandingPath() { return landingPath; }
    public void setLandingPath(String landingPath) { this.landingPath = landingPath; }
    public String getReferrer() { return referrer; }
    public void setReferrer(String referrer) { this.referrer = referrer; }
    public String getVisitorSessionId() { return visitorSessionId; }
    public void setVisitorSessionId(String visitorSessionId) { this.visitorSessionId = visitorSessionId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getIpHash() { return ipHash; }
    public void setIpHash(String ipHash) { this.ipHash = ipHash; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
