-- Migration V34: Magic link authentication and email support

-- 1. Cho phép tài khoản đăng ký chỉ bằng email (phone trở thành optional)
ALTER TABLE app.users ALTER COLUMN phone DROP NOT NULL;
DROP INDEX IF EXISTS app.ux_users_phone;
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_phone ON app.users(phone) WHERE phone IS NOT NULL;

-- 2. Đảm bảo email là duy nhất (không phân biệt hoa thường)
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_email ON app.users(lower(email)) WHERE email IS NOT NULL;

-- 3. Bảng lưu trữ liên kết đăng nhập một lần (Magic Login Token)
CREATE TABLE IF NOT EXISTS app.magic_login_tokens (
    id VARCHAR(36) NOT NULL,
    email VARCHAR(255) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    device_id VARCHAR(100) NULL,
    request_ip_hash VARCHAR(64) NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_magic_login_tokens PRIMARY KEY (id),
    CONSTRAINT ck_magic_login_tokens_status CHECK (status IN ('PENDING', 'USED', 'EXPIRED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_magic_tokens_hash ON app.magic_login_tokens(token_hash);
CREATE INDEX IF NOT EXISTS ix_magic_tokens_email_status ON app.magic_login_tokens(email, status);
