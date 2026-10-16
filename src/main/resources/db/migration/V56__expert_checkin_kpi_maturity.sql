-- A bounded pending worklist: do not rescan all historical bookings every heartbeat.
ALTER TABLE app.consultation_requests ADD COLUMN entry_kpi_finalized_at TIMESTAMPTZ;
CREATE INDEX ix_consultation_entry_kpi_due ON app.consultation_requests(scheduled_starts_at,id)
    WHERE entry_kpi_finalized_at IS NULL AND scheduled_starts_at IS NOT NULL
      AND status IN ('PENDING_CONSULTATION','COMPLETED');
