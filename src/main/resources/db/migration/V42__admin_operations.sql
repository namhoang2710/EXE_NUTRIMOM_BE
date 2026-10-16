-- Preserve older APIs while recording the immutable origin of new payment orders.
ALTER TABLE app.payment_orders ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'LEGACY_UNKNOWN';
UPDATE app.payment_orders SET source=CASE
  WHEN payos_payment_link_id LIKE 'MOCK-%' THEN 'DEMO'
  WHEN payos_payment_link_id IS NOT NULL AND length(trim(payos_payment_link_id))>0 THEN 'REAL'
  ELSE 'LEGACY_UNKNOWN' END;
ALTER TABLE app.payment_orders ADD CONSTRAINT ck_payment_order_source
  CHECK (source IN ('REAL','DEMO','LEGACY_UNKNOWN'));
CREATE INDEX ix_payment_admin_paid ON app.payment_orders(source,paid_at,id) WHERE status='PAID';
CREATE INDEX ix_payment_admin_created ON app.payment_orders(source,created_at DESC,id);
CREATE INDEX ix_consultation_admin_status_created ON app.consultation_requests(status,created_at,id);
CREATE INDEX ix_consultation_admin_completed ON app.consultation_requests(completed_at,id) WHERE status='COMPLETED';
CREATE INDEX ix_consultation_slots_admin_day ON app.consultation_slots(slot_date,start_time,id);
CREATE INDEX ix_subscription_admin_effective ON app.user_subscriptions(status,end_date,user_id);
CREATE INDEX ix_users_admin_created ON app.users(created_at,id);

-- Scope dedup to admin new-support events only. Lifecycle events for users remain untouched.
ALTER TABLE app.notifications ADD COLUMN dedup_key VARCHAR(160);
WITH ranked AS (
 SELECT id, first_value(id) OVER (PARTITION BY user_id,source_id ORDER BY created_at,id) keeper,
        min(read_at) OVER (PARTITION BY user_id,source_id) read_once
 FROM app.notifications
 WHERE type='CONTACT' AND source_type='CONTACT_REQUEST' AND source_id IS NOT NULL
   AND deep_link LIKE 'nutrimom://admin/contact-requests/%'
), updated AS (
 UPDATE app.notifications n SET read_at=r.read_once
 FROM ranked r WHERE n.id=r.keeper AND r.id=r.keeper AND r.read_once IS NOT NULL
 RETURNING n.id
)
DELETE FROM app.notifications n USING ranked r WHERE n.id=r.id AND r.id<>r.keeper;
UPDATE app.notifications SET dedup_key='ADMIN_CONTACT_NEW:'||user_id||':'||source_id
 WHERE type='CONTACT' AND source_type='CONTACT_REQUEST' AND source_id IS NOT NULL
   AND deep_link LIKE 'nutrimom://admin/contact-requests/%';
ALTER TABLE app.notifications ADD CONSTRAINT ux_admin_notification_event UNIQUE (dedup_key);
