SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

-- Lịch chuyển sang mô hình opt-out: mỗi chuyên gia mặc định mở 08:00-20:00 (khung 30 phút).
-- Bảng consultation_slots không còn là "danh sách lịch trống" mà là danh sách ô ĐÃ BỊ CHIẾM:
-- BOOKED (user đặt) hoặc CLOSED (chuyên gia đóng). Không có dòng nghĩa là ô còn trống.
-- Các dòng OPEN do chuyên gia mở tay trước đây vì thế không còn ý nghĩa; dòng nào đang được
-- một yêu cầu tư vấn trỏ tới thì giữ lại (khóa ngoại) và chuyển thành BOOKED.
UPDATE app.consultation_slots
SET status = 'BOOKED', updated_at = SYSDATETIMEOFFSET()
WHERE status = 'OPEN'
  AND id IN (SELECT slot_id FROM app.consultation_requests WHERE slot_id IS NOT NULL);
GO

DELETE FROM app.consultation_slots WHERE status = 'OPEN';
GO

ALTER TABLE app.consultation_slots DROP CONSTRAINT df_consultation_slots_status;
GO

ALTER TABLE app.consultation_slots DROP CONSTRAINT ck_consultation_slots_status;
GO

ALTER TABLE app.consultation_slots ADD CONSTRAINT ck_consultation_slots_status
    CHECK (status IN ('BOOKED', 'CLOSED'));
GO

-- Nghỉ trọn ngày tách riêng khỏi việc đóng từng khung giờ: bật rồi tắt "nghỉ cả ngày"
-- không được xóa mất những khung giờ chuyên gia đã cố ý đóng tay trước đó.
CREATE TABLE app.expert_day_offs (
    id NVARCHAR(36) NOT NULL,
    expert_user_id NVARCHAR(36) NOT NULL,
    off_date DATE NOT NULL,
    created_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_expert_day_offs PRIMARY KEY (id),
    CONSTRAINT fk_expert_day_offs_expert FOREIGN KEY (expert_user_id)
        REFERENCES app.expert_profiles(user_id),
    CONSTRAINT ux_expert_day_offs_expert_date UNIQUE (expert_user_id, off_date)
);
GO
