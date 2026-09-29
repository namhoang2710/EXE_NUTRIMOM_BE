-- Sổ địa chỉ push (spec mục 18 "POST /devices"): mỗi lần cài app sinh một device_id ổn định,
-- mỗi device_id giữ đúng một push token. Token KHÔNG lưu thô — cột cipher là AES-256-GCM base64,
-- cột hash là SHA-256 hex chỉ để tra cứu/khử trùng vì ciphertext không tất định (IV ngẫu nhiên).
CREATE TABLE app.push_devices (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    device_id VARCHAR(100) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    push_token_cipher VARCHAR(600) NOT NULL,
    push_token_hash VARCHAR(64) NOT NULL,
    app_version VARCHAR(30) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_push_devices PRIMARY KEY (id),
    CONSTRAINT fk_push_devices_user FOREIGN KEY (user_id)
        REFERENCES app.users(id),
    CONSTRAINT ck_push_devices_platform CHECK (
        platform IN ('ANDROID', 'IOS', 'WEB')),
    -- Khóa upsert của POST /devices.
    CONSTRAINT ux_push_devices_user_device UNIQUE (user_id, device_id),
    -- Một token chỉ thuộc một tài khoản: user B đăng nhập trên cùng máy thì token chuyển sang B,
    -- tránh push nội dung của B tới thiết bị đang hiển thị phiên của A.
    CONSTRAINT ux_push_devices_token_hash UNIQUE (push_token_hash)
);

-- Lấy danh sách thiết bị còn hoạt động của user khi gửi push.
CREATE INDEX ix_push_devices_user_active
    ON app.push_devices(user_id, active);

-- Hộp thông báo in-app (spec mục 18 "GET /notifications"). read_at là trạng thái riêng của từng
-- thông báo nên không suy ra được từ bảng nguồn; deep_link để app mở đúng màn hình.
CREATE TABLE app.notifications (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    deep_link VARCHAR(300) NULL,
    source_type VARCHAR(40) NULL,
    source_id VARCHAR(36) NULL,
    read_at TIMESTAMP WITH TIME ZONE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id)
        REFERENCES app.users(id),
    CONSTRAINT ck_notifications_type CHECK (
        type IN ('CONSULTATION', 'CONTACT', 'FAMILY', 'REMINDER', 'SYSTEM'))
);

-- Khớp đúng keyset cursor (created_at DESC, id DESC) của GET /notifications.
CREATE INDEX ix_notifications_user_created
    ON app.notifications(user_id, created_at DESC, id DESC);

-- Đếm số chưa đọc cho dashboard.
CREATE INDEX ix_notifications_user_unread
    ON app.notifications(user_id, read_at);

-- Activity feed (spec mục 18 "GET /activity-feed").
-- CỐ Ý không có cột free-text/preview: title là chuỗi đã render từ template an toàn, nên không thể
-- rò nội dung hồ sơ y tế qua message preview như spec cấm. Việc gì nhạy cảm thì ghi OWNER_ONLY.
CREATE TABLE app.activity_events (
    id VARCHAR(36) NOT NULL,
    subject_user_id VARCHAR(36) NOT NULL,
    actor_user_id VARCHAR(36) NULL,
    pregnancy_id VARCHAR(36) NULL,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_activity_events PRIMARY KEY (id),
    CONSTRAINT fk_activity_events_subject FOREIGN KEY (subject_user_id)
        REFERENCES app.users(id),
    CONSTRAINT fk_activity_events_actor FOREIGN KEY (actor_user_id)
        REFERENCES app.users(id),
    CONSTRAINT fk_activity_events_pregnancy FOREIGN KEY (pregnancy_id)
        REFERENCES app.pregnancies(id),
    CONSTRAINT ck_activity_events_type CHECK (
        type IN ('CONSULTATION_ACCEPTED', 'CONSULTATION_COMPLETED', 'CONSULTATION_CANCELLED',
                 'CONTACT_COMPLETED', 'FAMILY_TASK_ASSIGNED', 'FAMILY_TASK_COMPLETED')),
    CONSTRAINT ck_activity_events_visibility CHECK (
        visibility IN ('OWNER_ONLY', 'FAMILY'))
);

-- Feed của chính chủ.
CREATE INDEX ix_activity_events_subject_created
    ON app.activity_events(subject_user_id, created_at DESC, id DESC);

-- Feed chia sẻ cho thành viên gia đình theo thai kỳ.
CREATE INDEX ix_activity_events_pregnancy_created
    ON app.activity_events(pregnancy_id, created_at DESC, id DESC);
