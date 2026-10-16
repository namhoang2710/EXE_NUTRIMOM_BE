-- Reconcile the deployed V42/V46/V51/V53 history without rewriting applied SQL.
-- V54 already supplies tracking_started_at, attendance_finalized_at and its due index.
-- No historical revenue backfill, notification replay or synthetic check-in evidence.

CREATE INDEX IF NOT EXISTS ix_users_admin_created ON app.users(created_at,id);
DROP INDEX IF EXISTS app.ix_admin_expert_performance_schedule;

ALTER TABLE app.consultation_requests DROP CONSTRAINT ck_consultation_canonical_window;
ALTER TABLE app.consultation_requests ADD CONSTRAINT ck_consultation_canonical_window CHECK (
    (scheduled_starts_at IS NULL AND scheduled_ends_at IS NULL)
    OR (scheduled_starts_at IS NOT NULL AND scheduled_ends_at IS NOT NULL
        AND scheduled_starts_at < scheduled_ends_at)
);

CREATE OR REPLACE FUNCTION app.recognize_subscription_upgrade()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    price INTEGER;
BEGIN
    IF NEW.status <> 'ACTIVE' OR NEW.plan_tier NOT IN ('PLAN_99K', 'PLAN_399K')
       OR NEW.end_date <= NEW.start_date OR NEW.end_date <= statement_timestamp() THEN RETURN NEW; END IF;
    -- Re-saving an active tier or editing its duration is not another purchase.
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'ACTIVE' AND OLD.plan_tier = NEW.plan_tier
           AND OLD.end_date > statement_timestamp() THEN RETURN NEW; END IF;
    END IF;
    -- Serialize separate inserts for a customer as well as repeated updates of one row.
    PERFORM 1 FROM app.users WHERE id=NEW.user_id FOR UPDATE;
    -- Only customer accounts; inserting a duplicate overlapping entitlement is not an upgrade.
    IF NOT EXISTS (SELECT 1 FROM app.user_roles WHERE user_id=NEW.user_id AND role='USER')
       OR EXISTS (SELECT 1 FROM app.user_roles WHERE user_id=NEW.user_id AND role IN ('ADMIN','EXPERT'))
       OR (TG_OP = 'INSERT' AND EXISTS (
           SELECT 1 FROM app.user_subscriptions s WHERE s.user_id=NEW.user_id AND s.id<>NEW.id
           AND s.status='ACTIVE' AND s.plan_tier IN ('PLAN_99K','PLAN_399K')
           AND s.start_date<NEW.end_date AND s.end_date>NEW.start_date)) THEN RETURN NEW; END IF;
    -- PaymentService flushes the paid order before changing the subscription.
    -- xmin also covers older SQL/app writers in the same transaction without a new app flag.
    IF EXISTS (SELECT 1 FROM app.payment_orders p WHERE p.user_id=NEW.user_id
        AND p.plan_tier=NEW.plan_tier AND p.status='PAID' AND p.paid_at IS NOT NULL
        AND p.recognition_key IS NULL
        AND p.xmin::text::numeric=mod(pg_current_xact_id()::text::numeric,4294967296))
        THEN RETURN NEW; END IF;
    price := CASE NEW.plan_tier WHEN 'PLAN_99K' THEN 99000 ELSE 399000 END;
    INSERT INTO app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,status,source,
        description,paid_at,created_at,updated_at,version,recognition_key)
    VALUES (gen_random_uuid()::text,nextval('app.subscription_revenue_order_code'),NEW.user_id,
        NEW.plan_tier,price,'VND','PAID','LEGACY_UNKNOWN','Ghi nhận gói dịch vụ',
        CASE WHEN TG_OP='INSERT' THEN NEW.start_date ELSE statement_timestamp() END,
        statement_timestamp(),statement_timestamp(),0,
        'SUB:'||NEW.id||':'||pg_current_xact_id()::text||':'||NEW.plan_tier)
    ON CONFLICT (recognition_key) WHERE recognition_key IS NOT NULL DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION app.reconcile_subscription_revenue_order()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status <> 'PAID' OR NEW.paid_at IS NULL OR NEW.recognition_key IS NOT NULL
       THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' THEN
        IF OLD.status='PAID' AND OLD.paid_at IS NOT NULL THEN RETURN NEW; END IF;
    END IF;
    DELETE FROM app.payment_orders p WHERE p.user_id=NEW.user_id AND p.plan_tier=NEW.plan_tier
        AND p.recognition_key LIKE 'SUB:%'
        AND p.xmin::text::numeric=mod(pg_current_xact_id()::text::numeric,4294967296);
    RETURN NEW;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='app.payment_orders'::regclass
                   AND tgname='trg_payment_subscription_revenue' AND NOT tgisinternal) THEN
        CREATE TRIGGER trg_payment_subscription_revenue
        AFTER INSERT OR UPDATE OF status,paid_at ON app.payment_orders
        FOR EACH ROW EXECUTE FUNCTION app.reconcile_subscription_revenue_order();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='app.consultation_monitor_events'::regclass
                   AND tgname='trg_admin_realtime_collect' AND NOT tgisinternal) THEN
        CREATE TRIGGER trg_admin_realtime_collect
        AFTER INSERT OR UPDATE OR DELETE ON app.consultation_monitor_events
        FOR EACH ROW EXECUTE FUNCTION app.collect_admin_realtime_change();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='app.consultation_monitor_events'::regclass
                   AND tgname='trg_admin_realtime_flush' AND NOT tgisinternal) THEN
        CREATE CONSTRAINT TRIGGER trg_admin_realtime_flush
        AFTER INSERT OR UPDATE OR DELETE ON app.consultation_monitor_events
        DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app.flush_admin_realtime_changes();
    END IF;
END;
$$;
