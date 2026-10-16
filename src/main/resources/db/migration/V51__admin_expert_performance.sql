-- New read projection uses existing booking/review/provider evidence; no synthetic attendance backfill.
CREATE INDEX ix_admin_expert_performance_schedule
    ON app.consultation_requests(scheduled_date,expert_user_id)
    WHERE status IN ('PENDING_CONSULTATION','COMPLETED');

-- Reuse commit-time, rollback-safe, coalesced dashboard revisions from V50.
DO $$ DECLARE table_name TEXT; BEGIN
    FOREACH table_name IN ARRAY ARRAY['consultation_reviews','consultation_video_sessions'] LOOP
        EXECUTE format('CREATE TRIGGER trg_admin_realtime_collect AFTER INSERT OR UPDATE OR DELETE ON app.%I FOR EACH ROW EXECUTE FUNCTION app.collect_admin_realtime_change()',table_name);
        EXECUTE format('CREATE CONSTRAINT TRIGGER trg_admin_realtime_flush AFTER INSERT OR UPDATE OR DELETE ON app.%I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app.flush_admin_realtime_changes()',table_name);
    END LOOP;
END $$;
