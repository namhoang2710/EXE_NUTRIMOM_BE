-- Reconcile databases that applied the earlier V53 shape before presence
-- coverage and scan finalization were added. Do not infer historic attendance
-- or start reliable tracking from an old monitor heartbeat.
ALTER TABLE app.consultation_monitor_state
  ADD COLUMN IF NOT EXISTS tracking_started_at timestamptz;

ALTER TABLE app.consultation_requests
  ADD COLUMN IF NOT EXISTS attendance_finalized_at timestamptz;

CREATE INDEX IF NOT EXISTS ix_consultation_monitor_due
  ON app.consultation_requests(scheduled_ends_at,id)
  WHERE attendance_finalized_at IS NULL AND scheduled_ends_at IS NOT NULL
    AND status IN ('PENDING_CONSULTATION','COMPLETED');
