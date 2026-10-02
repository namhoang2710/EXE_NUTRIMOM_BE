-- Lịch nhắc nhở do mẹ bầu tự tạo (spec mục 10, task 22). Đây là nguồn dữ liệu GHI duy nhất của
-- module calendar: hai nguồn còn lại (hồ sơ y tế, buổi tư vấn đã được chuyên gia xác nhận) chỉ
-- được ĐỌC từ app.medical_records và app.consultation_requests, không nhân bản sang đây — tránh
-- hai bản sự thật phải đồng bộ.
CREATE TABLE app.calendar_reminders (
    id VARCHAR(36) NOT NULL,
    owner_user_id VARCHAR(36) NOT NULL,
    -- NULL được phép: nhắc lịch không nhất thiết thuộc một thai kỳ (vd khám tổng quát sau sinh).
    pregnancy_id VARCHAR(36) NULL,
    -- Nguồn gốc khi người dùng bấm "thêm vào lịch nhắc nhở" từ một hồ sơ y tế. Tiêu đề/cơ sở được
    -- COPY lúc tạo chứ không join lại: hồ sơ có thể bị xoá mềm mà nhắc lịch vẫn phải hiện.
    source_record_id VARCHAR(36) NULL,
    type VARCHAR(20) NOT NULL,
    title VARCHAR(255) NOT NULL,
    -- Cơ sở y tế: copy từ hồ sơ y tế khi tạo bằng nút "thêm vào lịch nhắc nhở", hoặc người dùng tự
    -- nhập. Dùng để render dòng phụ của ô lịch mà không phải join ngược về bảng nguồn.
    facility_name VARCHAR(255) NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Múi giờ người dùng chọn lúc tạo, để render lại đúng ngày/giờ địa phương kể cả khi họ đổi
    -- tuỳ chọn hoặc đang đi nước ngoài. starts_at vẫn là mốc tuyệt đối (UTC).
    timezone VARCHAR(50) NOT NULL,
    note TEXT NULL,
    remind_minutes_before INT NULL,
    -- Cột DẪN XUẤT = starts_at - remind_minutes_before phút, service ghi lại mỗi lần một trong hai
    -- cột nguồn đổi. Cố ý không tính trong câu query của job: phép trừ interval theo cột không
    -- index được trên Postgres.
    remind_at TIMESTAMP WITH TIME ZONE NULL,
    status VARCHAR(20) NOT NULL,
    -- Chốt chặn "gửi đúng một lần": job claim bằng UPDATE có điều kiện notified_at IS NULL và chỉ
    -- gửi thông báo khi rowcount = 1. Cố ý KHÔNG bump version — đây là cờ vận hành của job, không
    -- phải thay đổi dữ liệu người dùng nên không được gây VERSION_CONFLICT cho client.
    notified_at TIMESTAMP WITH TIME ZONE NULL,
    -- Dành sẵn cho recurrence (RRULE) bản sau; v1 luôn NULL. Đặt cột từ giờ để không phải migrate
    -- lại bảng khi mở tính năng lặp.
    recurrence_rule VARCHAR(255) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT pk_calendar_reminders PRIMARY KEY (id),
    CONSTRAINT fk_calendar_reminders_owner FOREIGN KEY (owner_user_id)
        REFERENCES app.users(id),
    CONSTRAINT fk_calendar_reminders_pregnancy FOREIGN KEY (pregnancy_id)
        REFERENCES app.pregnancies(id),
    CONSTRAINT fk_calendar_reminders_source_record FOREIGN KEY (source_record_id)
        REFERENCES app.medical_records(id),
    CONSTRAINT ck_calendar_reminders_type CHECK (
        type IN ('FOLLOW_UP', 'ROUTINE_CHECKUP', 'CUSTOM')),
    CONSTRAINT ck_calendar_reminders_status CHECK (
        status IN ('SCHEDULED', 'DONE', 'CANCELLED')),
    -- Chặn offset vô hạn ở tầng DB: job phải có chân trời quét hữu hạn (tối đa 7 ngày).
    CONSTRAINT ck_calendar_reminders_remind_offset CHECK (
        remind_minutes_before IS NULL
        OR (remind_minutes_before >= 0 AND remind_minutes_before <= 10080)),
    -- remind_at chỉ có nghĩa khi có offset; giữ hai cột không lệch nhau.
    CONSTRAINT ck_calendar_reminders_remind_at CHECK (
        (remind_minutes_before IS NULL AND remind_at IS NULL)
        OR (remind_minutes_before IS NOT NULL AND remind_at IS NOT NULL))
);

-- Khớp đúng truy vấn lịch: của một người, trong một khoảng [from, to), sắp theo thời điểm bắt đầu.
CREATE INDEX ix_calendar_reminders_owner_starts
    ON app.calendar_reminders(owner_user_id, starts_at, id)
    WHERE deleted_at IS NULL;

-- Hàng đợi "tới hạn mà chưa gửi" luôn rất nhỏ so với cả bảng, nên partial index giữ cho mỗi lượt
-- quét 5 phút không phải đọc toàn bảng.
CREATE INDEX ix_calendar_reminders_due
    ON app.calendar_reminders(remind_at)
    WHERE deleted_at IS NULL AND notified_at IS NULL AND status = 'SCHEDULED';

-- Tra ngược "hồ sơ y tế này đã có nhắc lịch nào chưa" để FE không tạo trùng.
CREATE INDEX ix_calendar_reminders_source_record
    ON app.calendar_reminders(source_record_id)
    WHERE deleted_at IS NULL AND source_record_id IS NOT NULL;
