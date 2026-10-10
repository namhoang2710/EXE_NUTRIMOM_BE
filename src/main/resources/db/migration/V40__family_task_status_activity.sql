-- Track every real family-task status transition while preserving the existing
-- FAMILY_TASK_COMPLETED activity type for completed transitions.
ALTER TABLE app.activity_events
    DROP CONSTRAINT ck_activity_events_type;

ALTER TABLE app.activity_events
    ADD CONSTRAINT ck_activity_events_type CHECK (
        type IN ('CONSULTATION_ACCEPTED', 'CONSULTATION_COMPLETED', 'CONSULTATION_CANCELLED',
                 'CONTACT_COMPLETED', 'FAMILY_TASK_ASSIGNED',
                 'FAMILY_TASK_STATUS_CHANGED', 'FAMILY_TASK_COMPLETED'));
