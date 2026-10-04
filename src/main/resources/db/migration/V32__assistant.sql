CREATE TABLE app.assistant_preferences (
    user_id VARCHAR(36) PRIMARY KEY REFERENCES app.users(id) ON DELETE CASCADE,
    cloud_consent BOOLEAN NOT NULL DEFAULT FALSE,
    use_profile BOOLEAN NOT NULL DEFAULT FALSE,
    use_pregnancy BOOLEAN NOT NULL DEFAULT FALSE,
    use_medical_records BOOLEAN NOT NULL DEFAULT FALSE,
    context_version BIGINT NOT NULL DEFAULT 0,
    usage_day DATE,
    ai_requests INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE app.assistant_conversations (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id VARCHAR(36) NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    title VARCHAR(100) NOT NULL,
    context_version BIGINT NOT NULL,
    processing_message_id VARCHAR(36),
    processing_since TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_assistant_conversations_owner ON app.assistant_conversations(owner_user_id, updated_at DESC);
CREATE INDEX ix_assistant_conversations_expiry ON app.assistant_conversations(updated_at);
CREATE TABLE app.assistant_messages (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL REFERENCES app.assistant_conversations(id) ON DELETE CASCADE,
    client_message_id VARCHAR(36) NOT NULL,
    role VARCHAR(12) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content TEXT NOT NULL,
    response_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(conversation_id, client_message_id, role)
);
CREATE INDEX ix_assistant_messages_conversation ON app.assistant_messages(conversation_id, created_at, id);
CREATE TABLE app.assistant_quota (
    id VARCHAR(20) PRIMARY KEY,
    usage_day DATE,
    requests INTEGER NOT NULL DEFAULT 0,
    reserved_tokens BIGINT NOT NULL DEFAULT 0
);
INSERT INTO app.assistant_quota(id) VALUES ('global');
