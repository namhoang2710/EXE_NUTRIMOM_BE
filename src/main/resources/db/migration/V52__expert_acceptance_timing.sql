-- New evidence only. updated_at is not an acceptance timestamp; do not invent history.
ALTER TABLE app.consultation_requests
    ADD COLUMN acceptance_opened_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN accepted_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_consultation_acceptance_timing CHECK
        (accepted_at IS NULL OR acceptance_opened_at IS NULL OR accepted_at >= acceptance_opened_at);

CREATE INDEX ix_consultation_acceptance_cohort
    ON app.consultation_requests (COALESCE(acceptance_opened_at,created_at),specialty,expert_user_id)
    WHERE assignment_type='RANDOM' AND status<>'CANCELLED';
CREATE INDEX ix_consultation_returning_customer
    ON app.consultation_requests (expert_user_id,user_id,completed_at)
    WHERE status='COMPLETED' AND completed_at IS NOT NULL;

-- The agreed service window follows SlotGrid: 08:00..20:00 every day in Vietnam.
-- O(1) calendar arithmetic, including partial minutes and exact closing-time boundaries.
CREATE FUNCTION app.consultation_acceptance_deadline(opened TIMESTAMP WITH TIME ZONE, sla_minutes INTEGER)
RETURNS TIMESTAMP WITH TIME ZONE LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE start_local TIMESTAMP := opened AT TIME ZONE 'Asia/Ho_Chi_Minh';
        available_seconds NUMERIC;
        remaining_seconds NUMERIC;
        full_days INTEGER;
BEGIN
    IF sla_minutes<1 OR sla_minutes>1440 THEN
        RAISE EXCEPTION 'acceptance SLA must be 1..1440 minutes';
    END IF;
    IF start_local::time < time '08:00' THEN
        start_local := start_local::date + time '08:00';
    ELSIF start_local::time >= time '20:00' THEN
        start_local := (start_local::date + 1) + time '08:00';
    END IF;
    available_seconds := extract(epoch FROM (start_local::date + time '20:00' - start_local));
    IF sla_minutes*60 <= available_seconds THEN
        RETURN (start_local + sla_minutes*interval '1 minute') AT TIME ZONE 'Asia/Ho_Chi_Minh';
    END IF;
    remaining_seconds := sla_minutes*60 - available_seconds;
    full_days := ceil(remaining_seconds/43200)::integer - 1;
    RETURN ((start_local::date + 1 + full_days) + time '08:00'
        + (remaining_seconds - full_days*43200)*interval '1 second') AT TIME ZONE 'Asia/Ho_Chi_Minh';
END; $$;

-- V50 already observes consultation_requests; timestamps reuse the same commit-time revision.
