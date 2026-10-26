-- Quên mật khẩu qua email (25/10/2026). Số V68 lấy từ flyway_schema_history của DB dùng chung (đang ở V67)
-- và mọi nhánh trên origin (không nhánh nào dùng V68+).
--
-- Chỉ lưu SHA-256 của token: token thô chỉ nằm trong email, lộ bảng này không đặt lại được mật khẩu.
-- Token là 32 byte ngẫu nhiên (không phải mã 6 chữ số như magic link) nên không dò được.
CREATE TABLE app.password_reset_tokens (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    request_ip_hash VARCHAR(64) NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_password_reset_tokens PRIMARY KEY (id),
    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES app.users(id) ON DELETE CASCADE,
    CONSTRAINT ck_password_reset_tokens_status CHECK (status IN ('PENDING', 'USED', 'REVOKED'))
);

CREATE UNIQUE INDEX ux_password_reset_tokens_hash ON app.password_reset_tokens(token_hash);
-- Phục vụ cooldown và giới hạn số lần/giờ theo người dùng, cùng việc huỷ token cũ đang chờ.
CREATE INDEX ix_password_reset_tokens_user_created ON app.password_reset_tokens(user_id, created_at DESC);
