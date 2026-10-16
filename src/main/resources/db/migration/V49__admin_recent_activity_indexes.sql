-- Each recent-activity source reads its newest eight rows before the union.
-- Match timestamp DESC / id ASC exactly, including the tie case for bulk-created rows.
CREATE INDEX ix_admin_recent_users ON app.users(created_at DESC,id ASC);
CREATE INDEX ix_admin_recent_payments ON app.payment_orders(paid_at DESC,id ASC)
    WHERE status='PAID' AND paid_at IS NOT NULL;
CREATE INDEX ix_admin_recent_consultations ON app.consultation_requests(completed_at DESC,id ASC)
    WHERE status='COMPLETED' AND completed_at IS NOT NULL;
CREATE INDEX ix_admin_recent_support ON app.contact_requests(completed_at DESC,id ASC)
    WHERE status='COMPLETED' AND completed_at IS NOT NULL;
