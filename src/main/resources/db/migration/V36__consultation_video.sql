CREATE TABLE app.consultation_video_sessions (
    request_id VARCHAR(36) PRIMARY KEY REFERENCES app.consultation_requests(id),
    room_name VARCHAR(80) NOT NULL UNIQUE,
    opens_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closes_at TIMESTAMP WITH TIME ZONE NOT NULL,
    user_joined_at TIMESTAMP WITH TIME ZONE,
    expert_joined_at TIMESTAMP WITH TIME ZONE,
    ended_at TIMESTAMP WITH TIME ZONE,
    cleanup_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_video_time_window CHECK (closes_at > opens_at)
);
CREATE INDEX ix_video_cleanup ON app.consultation_video_sessions(cleanup_at, closes_at);
