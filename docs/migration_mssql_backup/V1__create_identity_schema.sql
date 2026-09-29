SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;

CREATE TABLE app.users (
    id NVARCHAR(36) NOT NULL,
    phone NVARCHAR(20) NOT NULL,
    password_hash NVARCHAR(100) NULL,
    display_name NVARCHAR(100) NOT NULL,
    status NVARCHAR(20) NOT NULL,
    terms_accepted_at DATETIMEOFFSET(7) NULL,
    privacy_accepted_at DATETIMEOFFSET(7) NULL,
    version BIGINT NOT NULL CONSTRAINT df_users_version DEFAULT 0,
    created_at DATETIMEOFFSET(7) NOT NULL,
    updated_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);

CREATE UNIQUE INDEX ux_users_phone ON app.users(phone);

CREATE TABLE app.user_roles (
    user_id NVARCHAR(36) NOT NULL,
    role NVARCHAR(30) NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id)
        REFERENCES app.users(id) ON DELETE CASCADE,
    CONSTRAINT ck_user_roles_role CHECK (role IN ('USER', 'EXPERT', 'CONTENT_EDITOR', 'CONTENT_REVIEWER', 'CONTENT_PUBLISHER', 'ADMIN'))
);

CREATE TABLE app.refresh_tokens (
    id NVARCHAR(36) NOT NULL,
    user_id NVARCHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    device_id NVARCHAR(100) NULL,
    expires_at DATETIMEOFFSET(7) NOT NULL,
    revoked_at DATETIMEOFFSET(7) NULL,
    replaced_by_token_id NVARCHAR(36) NULL,
    created_at DATETIMEOFFSET(7) NOT NULL,
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
    id NVARCHAR(36) NOT NULL,
    phone NVARCHAR(20) NOT NULL,
    purpose NVARCHAR(20) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    status NVARCHAR(20) NOT NULL,
    attempts INT NOT NULL CONSTRAINT df_otp_attempts DEFAULT 0,
    max_attempts INT NOT NULL,
    device_id NVARCHAR(100) NULL,
    request_ip_hash CHAR(64) NULL,
    terms_accepted BIT NOT NULL CONSTRAINT df_otp_terms DEFAULT 0,
    expires_at DATETIMEOFFSET(7) NOT NULL,
    resend_available_at DATETIMEOFFSET(7) NOT NULL,
    verified_at DATETIMEOFFSET(7) NULL,
    created_at DATETIMEOFFSET(7) NOT NULL,
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
