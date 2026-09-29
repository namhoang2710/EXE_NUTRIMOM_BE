CREATE TABLE app.users (
    id VARCHAR(36) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    password_hash VARCHAR(100) NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    terms_accepted_at TIMESTAMP WITH TIME ZONE NULL,
    privacy_accepted_at TIMESTAMP WITH TIME ZONE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);

CREATE UNIQUE INDEX ux_users_phone ON app.users(phone);

CREATE TABLE app.user_roles (
    user_id VARCHAR(36) NOT NULL,
    role VARCHAR(30) NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id)
        REFERENCES app.users(id) ON DELETE CASCADE,
    CONSTRAINT ck_user_roles_role CHECK (role IN ('USER', 'EXPERT', 'CONTENT_EDITOR', 'CONTENT_REVIEWER', 'CONTENT_PUBLISHER', 'ADMIN'))
);

CREATE TABLE app.refresh_tokens (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    device_id VARCHAR(100) NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NULL,
    replaced_by_token_id VARCHAR(36) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id)
        REFERENCES app.users(id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_replacement FOREIGN KEY (replaced_by_token_id)
        REFERENCES app.refresh_tokens(id)
);

CREATE UNIQUE INDEX ux_refresh_tokens_hash ON app.refresh_tokens(token_hash);
CREATE INDEX ix_refresh_tokens_user_active
    ON app.refresh_tokens(user_id, revoked_at, expires_at);

CREATE TABLE app.otp_challenges (
    id VARCHAR(36) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL,
    device_id VARCHAR(100) NULL,
    request_ip_hash VARCHAR(64) NULL,
    terms_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resend_available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_otp_challenges PRIMARY KEY (id),
    CONSTRAINT ck_otp_purpose CHECK (purpose IN ('LOGIN', 'REGISTER')),
    CONSTRAINT ck_otp_status CHECK (status IN ('PENDING', 'VERIFIED', 'EXPIRED', 'SUPERSEDED')),
    CONSTRAINT ck_otp_attempts CHECK (attempts >= 0 AND max_attempts BETWEEN 1 AND 10)
);

CREATE UNIQUE INDEX ux_otp_one_pending
    ON app.otp_challenges(phone, purpose)
    WHERE status = 'PENDING';
CREATE INDEX ix_otp_phone_created
    ON app.otp_challenges(phone, created_at DESC);

