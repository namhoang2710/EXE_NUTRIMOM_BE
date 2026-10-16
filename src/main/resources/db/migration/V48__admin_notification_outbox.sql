-- Additive, keeps the existing user/expert notification contracts and data.
ALTER TABLE app.notifications ADD COLUMN audience VARCHAR(20) NOT NULL DEFAULT 'USER';
ALTER TABLE app.notifications ADD COLUMN category VARCHAR(30) NOT NULL DEFAULT 'GENERAL';
UPDATE app.notifications SET audience='ADMIN',category=CASE
    WHEN type='CONTACT' THEN 'SUPPORT'
    WHEN type='CONSULTATION' THEN 'APPOINTMENT'
    ELSE 'GENERAL' END
WHERE dedup_key LIKE 'ADMIN_%' OR deep_link LIKE 'nutrimom://admin/%';
ALTER TABLE app.notifications ADD CONSTRAINT ck_notification_audience CHECK (audience IN ('USER','ADMIN'));
ALTER TABLE app.notifications ADD CONSTRAINT ck_notification_category
    CHECK (category IN ('ACCOUNT','PAYMENT','APPOINTMENT','SUPPORT','GENERAL'));
CREATE INDEX ix_notifications_audience_feed ON app.notifications(user_id,audience,created_at DESC,id DESC);
CREATE INDEX ix_notifications_audience_unread ON app.notifications(user_id,audience,category)
    WHERE read_at IS NULL;
CREATE INDEX ix_notifications_category_feed ON app.notifications(user_id,category,created_at DESC,id DESC);

-- No historical backfill and no trigger on pg edits: events come from business API transactions.
CREATE TABLE app.admin_notification_outbox (
    id VARCHAR(36) PRIMARY KEY,
    event_key VARCHAR(160) NOT NULL UNIQUE,
    event_name VARCHAR(40) NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    deep_link VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts>=0),
    last_error VARCHAR(100)
);
CREATE INDEX ix_admin_notification_outbox_pending ON app.admin_notification_outbox(next_attempt_at,id)
    WHERE processed_at IS NULL;
