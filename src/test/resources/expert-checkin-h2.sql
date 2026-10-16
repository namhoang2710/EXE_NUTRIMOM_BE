CREATE TABLE IF NOT EXISTS app.expert_checkin_policy (id INTEGER PRIMARY KEY, started_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE TABLE IF NOT EXISTS app.expert_consultation_checkins (
    request_id VARCHAR(36) PRIMARY KEY REFERENCES app.consultation_requests(id) ON DELETE CASCADE,
    expert_user_id VARCHAR(36) NOT NULL REFERENCES app.expert_profiles(user_id),
    checked_in_at TIMESTAMP WITH TIME ZONE NOT NULL
);
