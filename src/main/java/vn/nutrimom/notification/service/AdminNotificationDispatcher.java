package vn.nutrimom.notification.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.notification.domain.AdminNotificationEvent;

/** One transaction per event; concurrent workers skip locked rows and never deliver partial fan-out. */
@Service
public class AdminNotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(AdminNotificationDispatcher.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public AdminNotificationDispatcher(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(15);
    }

    public int dispatchBatch() {
        int processed = 0;
        for (int i = 0; i < 50; i++) {
            try {
                if (!Boolean.TRUE.equals(transaction.execute(status -> dispatchNext()))) break;
                processed++;
            } catch (DeliveryFailure failure) {
                // The failed delivery rolled back. Postpone only that event, then continue the batch.
                transaction.executeWithoutResult(status -> jdbc.update("""
                        update app.admin_notification_outbox
                        set attempts=attempts+1, next_attempt_at=:next, last_error=:error
                        where id=:id and processed_at is null
                        """, new MapSqlParameterSource("id", failure.eventId)
                        .addValue("next", OffsetDateTime.now(ZoneOffset.UTC)
                                .plusSeconds(Math.min(900, 5L << Math.min(failure.attempts, 8))))
                        .addValue("error", failure.getCause().getClass().getSimpleName())));
                log.warn("Admin notification delivery deferred eventId={} error={}",
                        failure.eventId, failure.getCause().getClass().getSimpleName());
            }
        }
        return processed;
    }

    private boolean dispatchNext() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var rows = jdbc.query("""
                select id,event_name,source_id,body,deep_link,created_at,attempts
                from app.admin_notification_outbox
                where processed_at is null and next_attempt_at<=:now
                order by next_attempt_at,id limit 1 for update skip locked
                """, new MapSqlParameterSource("now", now), (rs, index) -> new Pending(
                rs.getString("id"), rs.getString("event_name"), rs.getString("source_id"),
                rs.getString("body"), rs.getString("deep_link"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getInt("attempts")));
        if (rows.isEmpty()) return false;
        Pending pending = rows.get(0);
        try {
            AdminNotificationEvent event = AdminNotificationEvent.valueOf(pending.eventName);
            var admins = jdbc.queryForList("""
                    select u.id from app.users u
                    where u.status='ACTIVE' and exists
                        (select 1 from app.user_roles r where r.user_id=u.id and r.role='ADMIN')
                    order by u.id
                    """, new MapSqlParameterSource(), String.class);
            var params = admins.stream().map(admin -> new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID().toString()).addValue("user", admin)
                    .addValue("type", event.type().name()).addValue("category", event.category().name())
                    .addValue("title", event.title()).addValue("body", pending.body)
                    .addValue("link", pending.deepLink).addValue("sourceType", event.sourceType())
                    .addValue("source", pending.sourceId).addValue("at", pending.createdAt)
                    .addValue("key", event.name() + ":" + admin + ":" + pending.sourceId))
                    .toArray(MapSqlParameterSource[]::new);
            if (params.length > 0) jdbc.batchUpdate("""
                    insert into app.notifications
                        (id,user_id,type,audience,category,title,body,deep_link,source_type,source_id,
                         dedup_key,created_at,updated_at,version)
                    select :id,:user,:type,'ADMIN',:category,:title,:body,:link,:sourceType,:source,
                           :key,:at,:at,0
                    where exists (select 1 from app.users u where u.id=:user and u.status='ACTIVE'
                        and exists (select 1 from app.user_roles r where r.user_id=u.id and r.role='ADMIN'))
                    on conflict do nothing
                    """, params);
            jdbc.update("""
                    update app.admin_notification_outbox
                    set processed_at=:now,last_error=null where id=:id
                    """, new MapSqlParameterSource("now", now).addValue("id", pending.id));
            return true;
        } catch (RuntimeException failure) {
            throw new DeliveryFailure(pending.id, pending.attempts, failure);
        }
    }

    private record Pending(String id, String eventName, String sourceId, String body,
            String deepLink, OffsetDateTime createdAt, int attempts) { }

    private static final class DeliveryFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String eventId;
        private final int attempts;
        DeliveryFailure(String eventId, int attempts, RuntimeException cause) {
            super("Admin notification delivery failed", cause);
            this.eventId = eventId;
            this.attempts = attempts;
        }
    }
}
