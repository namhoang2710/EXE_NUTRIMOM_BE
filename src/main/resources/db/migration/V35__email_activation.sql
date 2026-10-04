-- Migration V35: Support account activation via email
ALTER TABLE app.users DROP CONSTRAINT IF EXISTS ck_users_status;
ALTER TABLE app.users ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED', 'PENDING_ACTIVATION'));

ALTER TABLE app.users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMP WITH TIME ZONE NULL;
ALTER TABLE app.users ADD COLUMN IF NOT EXISTS email_activation_token_hash VARCHAR(64) NULL;
ALTER TABLE app.users ADD COLUMN IF NOT EXISTS email_activation_expires_at TIMESTAMP WITH TIME ZONE NULL;

CREATE INDEX IF NOT EXISTS ix_users_activation_token ON app.users(email_activation_token_hash) WHERE email_activation_token_hash IS NOT NULL;
