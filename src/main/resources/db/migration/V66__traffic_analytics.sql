-- V66__traffic_analytics.sql
-- Tracking lưu lượng người dùng từ mạng xã hội (TikTok, Facebook, Instagram, Google, Direct)

ALTER TABLE app.users ADD COLUMN IF NOT EXISTS acquisition_source VARCHAR(50) DEFAULT 'DIRECT';

CREATE TABLE IF NOT EXISTS app.traffic_logs (
    id VARCHAR(36) PRIMARY KEY,
    source VARCHAR(50) NOT NULL,
    medium VARCHAR(50),
    campaign VARCHAR(100),
    landing_path VARCHAR(255),
    referrer VARCHAR(500),
    visitor_session_id VARCHAR(100),
    user_id VARCHAR(36),
    ip_hash VARCHAR(64),
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_traffic_logs_source_created ON app.traffic_logs(source, created_at);
CREATE INDEX IF NOT EXISTS ix_traffic_logs_visitor_created ON app.traffic_logs(visitor_session_id, created_at);
CREATE INDEX IF NOT EXISTS ix_users_acquisition_source ON app.users(acquisition_source);
