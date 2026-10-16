-- New read projection uses existing booking/review/provider evidence; no synthetic attendance backfill.
-- Reuse ix_consultation_admin_scheduled / ix_consultation_requests_expert / review PK;
-- do not add a second overlapping scheduled-date index for this aggregate.

-- Reuse commit-time, rollback-safe, coalesced dashboard revisions from V50.
DO $$ DECLARE table_name TEXT; BEGIN
    FOREACH table_name IN ARRAY ARRAY['consultation_reviews','consultation_video_sessions'] LOOP
        EXECUTE format('CREATE TRIGGER trg_admin_realtime_collect AFTER INSERT OR UPDATE OR DELETE ON app.%I FOR EACH ROW EXECUTE FUNCTION app.collect_admin_realtime_change()',table_name);
        EXECUTE format('CREATE CONSTRAINT TRIGGER trg_admin_realtime_flush AFTER INSERT OR UPDATE OR DELETE ON app.%I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app.flush_admin_realtime_changes()',table_name);
    END LOOP;
END $$;
