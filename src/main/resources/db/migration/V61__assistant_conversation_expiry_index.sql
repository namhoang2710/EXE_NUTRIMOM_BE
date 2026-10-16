-- V32 as applied on the shared DB did not create this index; the line was added to the file
-- afterwards and has been removed again so V32 matches its applied checksum (598666640).
-- Create the index here so new and existing databases end up with the same schema.
-- It supports purging assistant conversations older than the retention window.
CREATE INDEX IF NOT EXISTS ix_assistant_conversations_expiry ON app.assistant_conversations(updated_at);
