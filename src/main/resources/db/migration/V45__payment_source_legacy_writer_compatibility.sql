-- Older payment writers share this database but omit source. Classify only their
-- recognizable provider/mock link IDs; a PAID status or checkout URL alone is not evidence.
CREATE OR REPLACE FUNCTION app.classify_legacy_payment_source()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source = 'LEGACY_UNKNOWN' THEN
        IF NEW.payos_payment_link_id LIKE 'MOCK-%' THEN
            NEW.source := 'DEMO';
        ELSIF NEW.payos_payment_link_id ~ '^[0-9a-fA-F]{32}$' THEN
            NEW.source := 'REAL';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_source_legacy_writer
BEFORE INSERT OR UPDATE OF source, payos_payment_link_id ON app.payment_orders
FOR EACH ROW EXECUTE FUNCTION app.classify_legacy_payment_source();

-- Preserve explicit classifications and timestamps. Do not activate, extend or
-- manufacture a payment/subscription; rows without link evidence remain unknown.
UPDATE app.payment_orders
SET source = CASE WHEN payos_payment_link_id LIKE 'MOCK-%' THEN 'DEMO' ELSE 'REAL' END,
    version = version + 1
WHERE source = 'LEGACY_UNKNOWN'
  AND (payos_payment_link_id LIKE 'MOCK-%' OR payos_payment_link_id ~ '^[0-9a-fA-F]{32}$');
