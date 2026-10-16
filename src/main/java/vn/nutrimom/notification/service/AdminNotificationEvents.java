package vn.nutrimom.notification.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.notification.domain.AdminNotificationEvent;

/** Enqueue in the business transaction: rollback also removes the event; no network/fan-out here. */
@Service
public class AdminNotificationEvents {
    private final NamedParameterJdbcTemplate jdbc;

    public AdminNotificationEvents(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AdminNotificationEvent event, String sourceId) {
        record(event, sourceId, sourceId, event.body());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AdminNotificationEvent event, String sourceId, String reference, String body) {
        UUID.fromString(sourceId);
        if (reference == null || !reference.matches("[a-zA-Z0-9-]{1,64}")
                || body == null || body.length() > 1000) {
            throw new IllegalArgumentException("Invalid admin notification metadata");
        }
        var at = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                insert into app.admin_notification_outbox
                    (id,event_key,event_name,source_id,body,deep_link,created_at,next_attempt_at,attempts)
                values (:id,:key,:event,:source,:body,:link,:at,:at,0)
                on conflict do nothing
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID().toString()).addValue("key", event.name() + ":" + sourceId)
                .addValue("event", event.name()).addValue("source", sourceId)
                .addValue("body", body).addValue("link", event.deepLink(reference)).addValue("at", at));
    }
}
