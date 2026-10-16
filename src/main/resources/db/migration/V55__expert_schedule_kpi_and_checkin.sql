-- Click evidence is separate from provider connection evidence. Never infer historical clicks.
CREATE TABLE app.expert_checkin_policy (
    id INTEGER PRIMARY KEY CHECK (id=1),
    started_at TIMESTAMPTZ NOT NULL
);
INSERT INTO app.expert_checkin_policy VALUES (1,statement_timestamp());

CREATE TABLE app.expert_consultation_checkins (
    request_id VARCHAR(36) PRIMARY KEY REFERENCES app.consultation_requests(id) ON DELETE CASCADE,
    expert_user_id VARCHAR(36) NOT NULL REFERENCES app.expert_profiles(user_id),
    checked_in_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_expert_checkins_expert ON app.expert_consultation_checkins(expert_user_id,checked_in_at);

DO $$ DECLARE table_name TEXT; BEGIN
    FOREACH table_name IN ARRAY ARRAY['expert_day_offs','expert_consultation_checkins'] LOOP
        EXECUTE format('CREATE TRIGGER trg_admin_realtime_collect AFTER INSERT OR UPDATE OR DELETE ON app.%I FOR EACH ROW EXECUTE FUNCTION app.collect_admin_realtime_change()',table_name);
        EXECUTE format('CREATE CONSTRAINT TRIGGER trg_admin_realtime_flush AFTER INSERT OR UPDATE OR DELETE ON app.%I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app.flush_admin_realtime_changes()',table_name);
    END LOOP;
END $$;
