-- Durable latest-state revisions, not an unbounded event history. No historical notification backfill.
CREATE TABLE app.admin_realtime_revisions (
    scope_key VARCHAR(80) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0)
);
INSERT INTO app.admin_realtime_revisions(scope_key,revision) VALUES ('DASHBOARD',0);

-- Collect keys transaction-locally; acquire revision locks only AFTER all business writes.
-- Sorted commit-time locking avoids introducing a global counter/user-row lock inversion.
CREATE FUNCTION app.collect_admin_realtime_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE keys JSONB := COALESCE(NULLIF(current_setting('nutrimom.realtime_keys',true),''),'{}')::jsonb;
BEGIN
    IF TG_OP='UPDATE' AND (to_jsonb(NEW)-ARRAY['version','updated_at'])=
                         (to_jsonb(OLD)-ARRAY['version','updated_at']) THEN RETURN NULL; END IF;
    IF TG_TABLE_NAME='notifications' THEN
        IF TG_OP<>'INSERT' AND OLD.audience='ADMIN' THEN
            keys := keys || jsonb_build_object('NOTIFICATIONS:'||OLD.user_id,true);
        END IF;
        IF TG_OP<>'DELETE' AND NEW.audience='ADMIN' THEN
            keys := keys || jsonb_build_object('NOTIFICATIONS:'||NEW.user_id,true);
        END IF;
    ELSE keys := keys || '{"DASHBOARD":true}'::jsonb;
    END IF;
    PERFORM set_config('nutrimom.realtime_keys',keys::text,true);
    RETURN NULL;
END; $$;

CREATE FUNCTION app.flush_admin_realtime_changes() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE keys JSONB := COALESCE(NULLIF(current_setting('nutrimom.realtime_keys',true),''),'{}')::jsonb;
        key TEXT;
BEGIN
    PERFORM set_config('nutrimom.realtime_keys','{}',true);
    FOR key IN SELECT jsonb_object_keys(keys) ORDER BY 1 LOOP
        INSERT INTO app.admin_realtime_revisions(scope_key,revision) VALUES (key,1)
        ON CONFLICT (scope_key) DO UPDATE SET revision=admin_realtime_revisions.revision+1;
    END LOOP;
    RETURN NULL;
END; $$;

DO $$ DECLARE table_name TEXT; BEGIN
    FOREACH table_name IN ARRAY ARRAY['users','user_roles','expert_profiles','consultation_requests',
        'consultation_slots','contact_requests','payment_orders','user_subscriptions',
        'admin_customer_metrics','notifications'] LOOP
        EXECUTE format('CREATE TRIGGER trg_admin_realtime_collect AFTER INSERT OR UPDATE OR DELETE ON app.%I FOR EACH ROW EXECUTE FUNCTION app.collect_admin_realtime_change()',table_name);
        EXECUTE format('CREATE CONSTRAINT TRIGGER trg_admin_realtime_flush AFTER INSERT OR UPDATE OR DELETE ON app.%I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app.flush_admin_realtime_changes()',table_name);
    END LOOP;
END $$;

-- Keep the initial entitlement for each changed row so repeated edits in ONE transaction
-- produce one net change, and a change reverted before commit produces no notification.
CREATE FUNCTION app.collect_admin_subscription_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changes JSONB := COALESCE(NULLIF(current_setting('nutrimom.subscription_changes',true),''),'{}')::jsonb;
        row_id TEXT;
        prior JSONB;
BEGIN
    IF TG_OP='UPDATE' AND (NEW.user_id,NEW.plan_tier,NEW.status,NEW.start_date,NEW.end_date)
        IS NOT DISTINCT FROM (OLD.user_id,OLD.plan_tier,OLD.status,OLD.start_date,OLD.end_date) THEN RETURN NULL; END IF;
    row_id := CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
    IF NOT changes ? row_id THEN
        IF TG_OP='INSERT' THEN prior := 'null'::jsonb;
        ELSE prior := jsonb_build_object('user_id',OLD.user_id,'plan_tier',OLD.plan_tier,
            'status',OLD.status,'start_date',OLD.start_date,'end_date',OLD.end_date); END IF;
        PERFORM set_config('nutrimom.subscription_changes',(changes||jsonb_build_object(row_id,prior))::text,true);
    END IF;
    RETURN NULL;
END; $$;

CREATE FUNCTION app.notify_admin_subscription_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changes JSONB := COALESCE(NULLIF(current_setting('nutrimom.subscription_changes',true),''),'{}')::jsonb;
        row_id TEXT; prior JSONB; current_row JSONB; customer TEXT; active_before BOOLEAN; active_after BOOLEAN;
        event_id TEXT := gen_random_uuid()::text;
BEGIN
    row_id := CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
    IF NOT changes ? row_id THEN RETURN NULL; END IF;
    prior := changes->row_id;
    PERFORM set_config('nutrimom.subscription_changes',(changes-row_id)::text,true);
    SELECT jsonb_build_object('user_id',s.user_id,'plan_tier',s.plan_tier,'status',s.status,
        'start_date',s.start_date,'end_date',s.end_date) INTO current_row
        FROM app.user_subscriptions s WHERE s.id=row_id;
    IF COALESCE(current_row,'null'::jsonb)=prior THEN RETURN NULL; END IF;
    customer := COALESCE(current_row->>'user_id',prior->>'user_id');
    IF NOT EXISTS(SELECT 1 FROM app.users u WHERE u.id=customer)
       OR NOT EXISTS(SELECT 1 FROM app.user_roles r WHERE r.user_id=customer AND r.role='USER')
       OR EXISTS(SELECT 1 FROM app.user_roles r WHERE r.user_id=customer AND r.role IN ('ADMIN','EXPERT'))
       THEN RETURN NULL; END IF;
    active_before := prior->>'status'='ACTIVE' AND prior->>'plan_tier' IN ('PLAN_99K','PLAN_399K')
        AND (prior->>'start_date')::timestamptz<=statement_timestamp()
        AND (prior->>'end_date')::timestamptz>statement_timestamp();
    active_after := current_row->>'status'='ACTIVE' AND current_row->>'plan_tier' IN ('PLAN_99K','PLAN_399K')
        AND (current_row->>'start_date')::timestamptz<=statement_timestamp()
        AND (current_row->>'end_date')::timestamptz>statement_timestamp();
    IF NOT (COALESCE(active_before,false) OR COALESCE(active_after,false)) THEN RETURN NULL; END IF;
    -- PaymentService has recorded the purchase/renewal by commit, regardless of ORM flush order.
    IF EXISTS(SELECT 1 FROM app.admin_notification_outbox e JOIN app.payment_orders p ON p.id=e.source_id
        WHERE p.user_id=customer AND e.event_name IN ('ADMIN_PAYMENT_PAID','ADMIN_PAYMENT_RENEWED')
        AND p.xmin::text::numeric=mod(pg_current_xact_id()::text::numeric,4294967296)) THEN RETURN NULL; END IF;
    INSERT INTO app.admin_notification_outbox(id,event_key,event_name,source_id,body,deep_link,
        created_at,next_attempt_at,attempts)
    VALUES(event_id,'ADMIN_SUBSCRIPTION_CHANGED:'||row_id||':'||pg_current_xact_id()::text,
        'ADMIN_SUBSCRIPTION_CHANGED',event_id,'Thông tin gói dịch vụ của khách hàng đã được cập nhật.',
        'nutrimom://admin/users/'||customer,statement_timestamp(),statement_timestamp(),0)
    ON CONFLICT DO NOTHING;
    RETURN NULL;
END; $$;

CREATE TRIGGER trg_admin_subscription_collect AFTER INSERT OR UPDATE OR DELETE ON app.user_subscriptions
    FOR EACH ROW EXECUTE FUNCTION app.collect_admin_subscription_change();
CREATE CONSTRAINT TRIGGER trg_admin_subscription_notify AFTER INSERT OR UPDATE OR DELETE ON app.user_subscriptions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app.notify_admin_subscription_change();
