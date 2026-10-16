package vn.nutrimom.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Schema mapping; transactional inserts and bounded SKIP LOCKED delivery use JDBC. */
@Entity
@Table(name = "admin_notification_outbox", schema = "app")
public class AdminNotificationOutboxEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "event_key", nullable = false, unique = true, length = 160) private String eventKey;
    @Column(name = "event_name", nullable = false, length = 40) private String eventName;
    @Column(name = "source_id", nullable = false, length = 36) private String sourceId;
    @Column(nullable = false, length = 1000) private String body;
    @Column(name = "deep_link", nullable = false, length = 300) private String deepLink;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "processed_at") private OffsetDateTime processedAt;
    @Column(name = "next_attempt_at", nullable = false) private OffsetDateTime nextAttemptAt;
    @Column(nullable = false) private int attempts;
    @Column(name = "last_error", length = 100) private String lastError;
}
