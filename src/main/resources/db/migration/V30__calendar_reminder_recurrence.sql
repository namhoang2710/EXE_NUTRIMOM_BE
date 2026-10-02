-- Nhắc nhở lặp lại (spec mục 10 "recurrence_rule"), mô hình giống báo thức điện thoại:
-- một dòng mô tả cả chuỗi, các lần lặp được TÍNH RA lúc đọc chứ không lưu sẵn. Lưu sẵn 90 dòng
-- cho 90 ngày thì đổi giờ một cái phải sửa 90 dòng, và tới ngày thứ 91 là hết.
--
-- V29 có cột recurrence_rule để dành cho một chuỗi RRULE; bỏ đi vì chưa từng có dòng nào ghi vào,
-- và mấy cột có cấu trúc dưới đây kiểm tra được ở tầng DB còn chuỗi RRULE thì không.
ALTER TABLE app.calendar_reminders DROP COLUMN recurrence_rule;

-- NULL = mốc một lần (hành vi cũ, giữ nguyên).
ALTER TABLE app.calendar_reminders ADD COLUMN repeat_rule VARCHAR(20) NULL;

-- DAILY: mỗi N ngày (2 = cách ngày). WEEKLY: mỗi N tuần. MONTHLY: mỗi N tháng.
ALTER TABLE app.calendar_reminders ADD COLUMN repeat_interval INT NULL;

-- Chỉ dùng cho WEEKLY: "MONDAY,WEDNESDAY,FRIDAY" (đúng tên java.time.DayOfWeek, để API và cột lưu
-- cùng một bảng chữ, không phải dịch qua lại). Rỗng thì lấy thứ của starts_at.
ALTER TABLE app.calendar_reminders ADD COLUMN repeat_days_of_week VARCHAR(60) NULL;

-- Nhiều mốc trong ngày: "08:00,20:00" — thuốc sáng/tối là một dòng, không phải hai nhắc nhở.
-- Rỗng thì lấy giờ của starts_at.
ALTER TABLE app.calendar_reminders ADD COLUMN repeat_times_of_day VARCHAR(60) NULL;

-- NULL = lặp vô hạn; FE nên gợi ý đặt tới ngày dự sinh.
ALTER TABLE app.calendar_reminders ADD COLUMN repeat_until DATE NULL;

-- Mốc lặp GẦN NHẤT đã bắn. Đây là khoá chống gửi trùng thay cho notified_at: job claim bằng
-- "... WHERE last_fired_occurrence IS NULL OR last_fired_occurrence < :occurrence", nên hai
-- instance cùng quét thì chỉ một bên ghi được. notified_at giờ chỉ còn ý nghĩa hiển thị
-- ("lần gửi gần nhất lúc nào").
ALTER TABLE app.calendar_reminders ADD COLUMN last_fired_occurrence TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE app.calendar_reminders ADD CONSTRAINT ck_calendar_reminders_repeat_rule CHECK (
    repeat_rule IS NULL OR repeat_rule IN ('DAILY', 'WEEKLY', 'MONTHLY'));

-- Hai cột phải cùng có hoặc cùng không: interval chỉ có nghĩa khi đang lặp.
ALTER TABLE app.calendar_reminders ADD CONSTRAINT ck_calendar_reminders_repeat_interval CHECK (
    (repeat_rule IS NULL AND repeat_interval IS NULL)
    OR (repeat_rule IS NOT NULL AND repeat_interval BETWEEN 1 AND 365));

-- Thứ trong tuần chỉ áp dụng cho WEEKLY.
ALTER TABLE app.calendar_reminders ADD CONSTRAINT ck_calendar_reminders_repeat_days CHECK (
    repeat_days_of_week IS NULL OR repeat_rule = 'WEEKLY');

-- Chuỗi lặp chạy hết thì remind_at về NULL trong khi remind_minutes_before vẫn còn giá trị, nên
-- ràng buộc hai-chiều của V29 không còn đúng. Giữ lại đúng chiều có ý nghĩa: có remind_at thì phải
-- có offset sinh ra nó.
ALTER TABLE app.calendar_reminders DROP CONSTRAINT ck_calendar_reminders_remind_at;

ALTER TABLE app.calendar_reminders ADD CONSTRAINT ck_calendar_reminders_remind_at CHECK (
    remind_at IS NULL OR remind_minutes_before IS NOT NULL);

-- remind_at giờ là "mốc nhắc của LẦN KẾ TIẾP", job tự dời tới sau mỗi lần bắn, nên điều kiện
-- notified_at IS NULL không còn đúng cho chuỗi lặp (bắn xong vẫn phải quét lại cho lần sau).
DROP INDEX IF EXISTS app.ix_calendar_reminders_due;

CREATE INDEX ix_calendar_reminders_due
    ON app.calendar_reminders(remind_at)
    WHERE deleted_at IS NULL AND status = 'SCHEDULED' AND remind_at IS NOT NULL;

-- Trạng thái của TỪNG lần lặp. Cố ý chỉ lưu NGOẠI LỆ: ngày nào người dùng bấm "đã uống" hoặc
-- "bỏ qua" mới có dòng. Không có dòng = chưa làm. Nhờ vậy một nhắc nhở hằng ngày kéo dài cả thai kỳ
-- vẫn chỉ tốn đúng số dòng bằng số lần người dùng thật sự bấm.
CREATE TABLE app.calendar_reminder_occurrences (
    id VARCHAR(36) NOT NULL,
    reminder_id VARCHAR(36) NOT NULL,
    -- Mốc tuyệt đối của đúng lần lặp đó, phải trùng khít một mốc tính ra từ quy tắc lặp.
    occurrence_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_calendar_reminder_occurrences PRIMARY KEY (id),
    CONSTRAINT fk_calendar_reminder_occurrences_reminder FOREIGN KEY (reminder_id)
        REFERENCES app.calendar_reminders(id),
    CONSTRAINT ck_calendar_reminder_occurrences_status CHECK (
        status IN ('DONE', 'SKIPPED')),
    -- Khoá upsert: bấm "đã uống" hai lần cho cùng một mốc không sinh dòng thứ hai.
    CONSTRAINT ux_calendar_reminder_occurrences_slot UNIQUE (reminder_id, occurrence_at)
);

-- Lấy trạng thái của mọi lần lặp trong cửa sổ đang vẽ lịch.
CREATE INDEX ix_calendar_reminder_occurrences_window
    ON app.calendar_reminder_occurrences(reminder_id, occurrence_at);
