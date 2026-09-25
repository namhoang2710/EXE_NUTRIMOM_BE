SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

-- Hộp thư hỗ trợ: user gửi thắc mắc, admin gọi điện giải đáp rồi đánh dấu hoàn tất.
CREATE TABLE app.contact_requests (
    id NVARCHAR(36) NOT NULL,
    user_id NVARCHAR(36) NOT NULL,
    topic NVARCHAR(20) NOT NULL,
    message NVARCHAR(MAX) NOT NULL,
    status NVARCHAR(20) NOT NULL,
    completed_at DATETIMEOFFSET(7) NULL,
    completed_by NVARCHAR(36) NULL,
    cancelled_at DATETIMEOFFSET(7) NULL,
    version BIGINT NOT NULL CONSTRAINT df_contact_requests_version DEFAULT 0,
    created_at DATETIMEOFFSET(7) NOT NULL,
    updated_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_contact_requests PRIMARY KEY (id),
    CONSTRAINT fk_contact_requests_user FOREIGN KEY (user_id)
        REFERENCES app.users(id),
    CONSTRAINT fk_contact_requests_completed_by FOREIGN KEY (completed_by)
        REFERENCES app.users(id),
    CONSTRAINT ck_contact_requests_topic CHECK (
        topic IN ('POLICY', 'APP_USAGE', 'ACCOUNT', 'OTHER')),
    CONSTRAINT ck_contact_requests_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'CANCELLED'))
);
GO

-- Lịch sử yêu cầu của user.
CREATE INDEX ix_contact_requests_user_created
    ON app.contact_requests(user_id, created_at DESC, id DESC);
GO

-- Hộp thư admin lọc theo trạng thái.
CREATE INDEX ix_contact_requests_status_created
    ON app.contact_requests(status, created_at DESC, id DESC);
GO
