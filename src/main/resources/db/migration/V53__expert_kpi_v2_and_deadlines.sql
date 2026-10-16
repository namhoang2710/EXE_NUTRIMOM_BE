-- Additive deployment: keep V52's legacy deadline function for calculationVersion=1.
ALTER TABLE app.consultation_requests
  ADD COLUMN scheduled_starts_at timestamptz,
  ADD COLUMN scheduled_ends_at timestamptz,
  ADD COLUMN preferred_date date,
  ADD COLUMN acceptance_deadline_at timestamptz,
  ADD COLUMN acceptance_policy_version integer,
  ADD CONSTRAINT ck_consultation_canonical_window CHECK
    ((scheduled_starts_at IS NULL AND scheduled_ends_at IS NULL) OR scheduled_starts_at < scheduled_ends_at),
  ADD CONSTRAINT ck_consultation_deadline CHECK
    (acceptance_deadline_at IS NULL OR (acceptance_opened_at IS NOT NULL AND acceptance_deadline_at >= acceptance_opened_at));

CREATE FUNCTION app.consultation_acceptance_deadline_v2(opened timestamptz)
RETURNS timestamptz LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT (CASE WHEN (opened AT TIME ZONE 'Asia/Ho_Chi_Minh')::time < time '08:00'
    THEN (opened AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + time '08:30'
    WHEN (opened AT TIME ZONE 'Asia/Ho_Chi_Minh')::time >= time '19:00'
    THEN (opened AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + 1 + time '08:30'
    ELSE (opened AT TIME ZONE 'Asia/Ho_Chi_Minh') + interval '30 minutes' END)
    AT TIME ZONE 'Asia/Ho_Chi_Minh'
$$;

-- Pending requests with trustworthy queue-entry timestamps adopt the new deadline.
-- No accepted history is rewritten; no invented acceptance/join timestamps or historical alerts.
UPDATE app.consultation_requests SET acceptance_deadline_at=app.consultation_acceptance_deadline_v2(acceptance_opened_at),
  acceptance_policy_version=2 WHERE assignment_type='RANDOM' AND status='PENDING_EXPERT' AND acceptance_opened_at IS NOT NULL;

CREATE TABLE app.consultation_monitor_state (
  id integer PRIMARY KEY CHECK(id=1), started_at timestamptz NOT NULL,
  checked_at timestamptz, healthy boolean NOT NULL DEFAULT false
);
INSERT INTO app.consultation_monitor_state(id,started_at) VALUES(1,statement_timestamp());
CREATE TABLE app.consultation_monitor_gaps (
  id bigserial PRIMARY KEY, starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
  CHECK(ends_at>=starts_at)
);
CREATE INDEX ix_consultation_monitor_gaps ON app.consultation_monitor_gaps(starts_at,ends_at);
CREATE TABLE app.consultation_monitor_events (
  request_id varchar(36) NOT NULL REFERENCES app.consultation_requests(id) ON DELETE CASCADE,
  event_kind varchar(30) NOT NULL, created_at timestamptz NOT NULL,
  PRIMARY KEY(request_id,event_kind)
);
CREATE INDEX ix_consultation_pending_deadline ON app.consultation_requests(acceptance_deadline_at,id)
  WHERE status='PENDING_EXPERT' AND assignment_type='RANDOM';
CREATE INDEX ix_consultation_monitored_start ON app.consultation_requests(scheduled_starts_at,id)
  WHERE status='PENDING_CONSULTATION';
