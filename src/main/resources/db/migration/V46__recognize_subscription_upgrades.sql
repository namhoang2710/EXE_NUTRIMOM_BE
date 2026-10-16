-- One revenue ledger for provider purchases and direct database plan activations.
-- Never rewrite subscription dates or manufacture a bank transaction/provider link.
ALTER TABLE app.payment_orders ADD COLUMN recognition_key VARCHAR(160);
CREATE UNIQUE INDEX ux_payment_subscription_recognition ON app.payment_orders(recognition_key)
    WHERE recognition_key IS NOT NULL;
CREATE SEQUENCE app.subscription_revenue_order_code START WITH 8000000000000000000;

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
           AND s.start_date<=NEW.start_date AND s.end_date>NEW.start_date)) THEN RETURN NEW; END IF;
    -- PaymentService flushes the paid order before changing the subscription.
    -- xmin also covers older SQL/app writers in the same transaction without a new app flag.
    IF EXISTS (SELECT 1 FROM app.payment_orders p WHERE p.user_id=NEW.user_id
        AND p.plan_tier=NEW.plan_tier AND p.status='PAID' AND p.paid_at IS NOT NULL
        AND p.recognition_key IS NULL AND p.xmin::text=pg_current_xact_id()::text)
        THEN RETURN NEW; END IF;
    price := CASE NEW.plan_tier WHEN 'PLAN_99K' THEN 99000 ELSE 399000 END;
    INSERT INTO app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,status,source,
        description,paid_at,created_at,updated_at,version,recognition_key)
    VALUES (gen_random_uuid()::text,nextval('app.subscription_revenue_order_code'),NEW.user_id,
        NEW.plan_tier,price,'VND','PAID','LEGACY_UNKNOWN','Ghi nhận gói dịch vụ',
        CASE WHEN TG_OP='INSERT' THEN NEW.start_date ELSE statement_timestamp() END,
        statement_timestamp(),statement_timestamp(),0,
        'SUB:'||NEW.id||':'||pg_current_xact_id()::text||':'||NEW.plan_tier)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_subscription_revenue
AFTER INSERT OR UPDATE OF plan_tier,status,start_date,end_date ON app.user_subscriptions
FOR EACH ROW EXECUTE FUNCTION app.recognize_subscription_upgrade();

-- Existing current paid tiers with no paid order receive one nominal-price entry.
-- An existing order (including a SQL-created PAID order) is already sufficient:
-- never add another entry for the two existing 180-day grants, or for renewals.
INSERT INTO app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,status,source,
    description,paid_at,created_at,updated_at,version,recognition_key)
SELECT gen_random_uuid()::text,nextval('app.subscription_revenue_order_code'),s.user_id,
    s.plan_tier,CASE s.plan_tier WHEN 'PLAN_99K' THEN 99000 ELSE 399000 END,
    'VND','PAID','LEGACY_UNKNOWN','Ghi nhận gói dịch vụ',s.start_date,
    statement_timestamp(),statement_timestamp(),0,'BASELINE:'||s.id
FROM (SELECT x.*,row_number() OVER (PARTITION BY user_id ORDER BY end_date DESC,updated_at DESC,id) rn
      FROM app.user_subscriptions x WHERE status='ACTIVE'
      AND plan_tier IN ('PLAN_99K','PLAN_399K') AND start_date<=statement_timestamp()
      AND end_date>statement_timestamp()) s
WHERE s.rn=1
  AND EXISTS (SELECT 1 FROM app.user_roles WHERE user_id=s.user_id AND role='USER')
  AND NOT EXISTS (SELECT 1 FROM app.user_roles WHERE user_id=s.user_id AND role IN ('ADMIN','EXPERT'))
  AND NOT EXISTS (SELECT 1 FROM app.payment_orders p WHERE p.user_id=s.user_id
      AND p.plan_tier=s.plan_tier AND p.status='PAID' AND p.paid_at IS NOT NULL)
ON CONFLICT DO NOTHING;
