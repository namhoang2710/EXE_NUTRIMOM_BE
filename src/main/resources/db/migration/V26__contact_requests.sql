-- Hộp thư hỗ trợ: user gửi thắc mắc, admin gọi điện giải đáp rồi đánh dấu hoàn tất.
CREATE TABLE app.contact_requests (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    topic VARCHAR(20) NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    completed_by VARCHAR(36) NULL,
    cancelled_at TIMESTAMP WITH TIME ZONE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
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

-- Lịch sử yêu cầu của user.
CREATE INDEX ix_contact_requests_user_created
    ON app.contact_requests(user_id, created_at DESC, id DESC);

-- Hộp thư admin lọc theo trạng thái.
CREATE INDEX ix_contact_requests_status_created
    ON app.contact_requests(status, created_at DESC, id DESC);

