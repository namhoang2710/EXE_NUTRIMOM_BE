SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

-- Nhật ký kiểm toán dùng chung (spec mục 19/21): ghi lại ai làm gì, lúc nào cho các hành động nhạy cảm.
-- actor_user_id để NULL cho login thất bại / ẩn danh. metadata là JSON đã redact (không chứa token/OTP/password).
CREATE TABLE app.audit_logs (
    id NVARCHAR(36) NOT NULL,
    actor_user_id NVARCHAR(36) NULL,
    action NVARCHAR(60) NOT NULL,
    result NVARCHAR(20) NULL,
    target_type NVARCHAR(60) NULL,
    target_id NVARCHAR(100) NULL,
    request_id NVARCHAR(100) NULL,
    ip NVARCHAR(45) NULL,
    metadata NVARCHAR(MAX) NULL,
    created_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_audit_logs PRIMARY KEY (id),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_user_id)
        REFERENCES app.users(id)
);
GO

CREATE INDEX ix_audit_logs_actor_created
    ON app.audit_logs(actor_user_id, created_at DESC);
GO

CREATE INDEX ix_audit_logs_action_created
    ON app.audit_logs(action, created_at DESC);
GO

CREATE INDEX ix_audit_logs_target
    ON app.audit_logs(target_type, target_id);
GO
