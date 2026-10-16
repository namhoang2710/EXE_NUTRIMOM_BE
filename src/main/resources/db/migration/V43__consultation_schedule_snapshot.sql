-- Keep operational scheduling history without retaining a live booked-slot association.
ALTER TABLE app.consultation_requests ADD COLUMN scheduled_slot_id VARCHAR(36);
ALTER TABLE app.consultation_requests ADD COLUMN scheduled_date DATE;
ALTER TABLE app.consultation_requests ADD COLUMN scheduled_start_time TIME;
ALTER TABLE app.consultation_requests ADD COLUMN scheduled_end_time TIME;
UPDATE app.consultation_requests c SET
    scheduled_slot_id=s.id,scheduled_date=s.slot_date,
    scheduled_start_time=s.start_time,scheduled_end_time=s.end_time
FROM app.consultation_slots s WHERE s.id=c.slot_id;
ALTER TABLE app.consultation_requests ADD CONSTRAINT ck_consultation_schedule_snapshot CHECK (
    (scheduled_slot_id IS NULL AND scheduled_date IS NULL AND scheduled_start_time IS NULL AND scheduled_end_time IS NULL)
    OR (scheduled_slot_id IS NOT NULL AND scheduled_date IS NOT NULL
        AND scheduled_start_time IS NOT NULL AND scheduled_end_time IS NOT NULL AND scheduled_end_time>scheduled_start_time)
);
CREATE INDEX ix_consultation_admin_scheduled ON app.consultation_requests(scheduled_date,scheduled_start_time,id)
    WHERE scheduled_date IS NOT NULL;
