-- One-time administrative reconciliation; NOT a Flyway migration.
-- Review supabase-v32-alignment.md and run a fresh Flyway validate first.
-- Expected sole validation error: V32, applied 598666640, resolved 570677793.
-- No application records are updated. The missing index and V32 history update
-- commit together; any failed guard or timeout rolls the transaction back.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

LOCK TABLE app.flyway_schema_history IN SHARE ROW EXCLUSIVE MODE;

DO $guard$
BEGIN
    IF (SELECT COUNT(*) FROM app.flyway_schema_history WHERE version = '32') <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V32 history entry';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM app.flyway_schema_history
        WHERE version = '32' AND script = 'V32__assistant.sql'
          AND description = 'assistant' AND type = 'SQL'
          AND checksum = 598666640 AND success = TRUE
    ) THEN
        RAISE EXCEPTION 'V32 history differs from the reviewed Supabase state';
    END IF;
    IF EXISTS (SELECT 1 FROM app.flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION 'Resolve failed migrations before this operation';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM app.flyway_schema_history
        WHERE version = '41' AND script = 'V41__private_file_storage_provider.sql'
          AND success = TRUE
    ) OR EXISTS (
        SELECT 1 FROM app.flyway_schema_history
        WHERE installed_rank > (
            SELECT installed_rank FROM app.flyway_schema_history WHERE version = '41'
        ) AND version IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Migration history has moved beyond the reviewed V41 state';
    END IF;
END
$guard$;

CREATE INDEX IF NOT EXISTS ix_assistant_conversations_expiry
    ON app.assistant_conversations(updated_at);

DO $verify$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_index
        WHERE indexrelid = 'app.ix_assistant_conversations_expiry'::regclass
          AND indisvalid AND indisready AND NOT indisunique
          AND pg_get_indexdef(indexrelid) =
              'CREATE INDEX ix_assistant_conversations_expiry ON app.assistant_conversations USING btree (updated_at)'
    ) THEN
        RAISE EXCEPTION 'Expiry index does not match the GitHub V32 definition';
    END IF;
    UPDATE app.flyway_schema_history
    SET checksum = 570677793
    WHERE version = '32' AND script = 'V32__assistant.sql'
      AND checksum = 598666640 AND success = TRUE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V32 changed before the checksum reconciliation';
    END IF;
END
$verify$;

COMMIT;
