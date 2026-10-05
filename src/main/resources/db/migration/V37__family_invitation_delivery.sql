-- Lời mời gia đình: trạng thái vòng đời + dấu vết đã gửi đi hay chưa.
--
-- Trước migration này lời mời chỉ có accepted_at, nên không thu hồi được một lời mời gửi nhầm
-- địa chỉ, và màn hình của mẹ bầu không phân biệt nổi "đã gửi email rồi" với "chưa gửi được".
--
-- Mỗi cột một câu ALTER: gộp nhiều ADD COLUMN vào một câu là cú pháp Postgres mà H2 không hiểu,
-- nên bài test migration sẽ không chạy nổi file này.

ALTER TABLE app.family_invitations
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE app.family_invitations
    ADD COLUMN revoked_at TIMESTAMP WITH TIME ZONE NULL;
ALTER TABLE app.family_invitations
    ADD COLUMN sent_at TIMESTAMP WITH TIME ZONE NULL;
ALTER TABLE app.family_invitations
    ADD COLUMN delivery_status VARCHAR(20) NULL;

UPDATE app.family_invitations SET status = 'ACCEPTED' WHERE accepted_at IS NOT NULL;

-- EXPIRED cố ý KHÔNG nằm trong CHECK: hết hạn suy ra từ expires_at lúc đọc. Lưu thành cột sẽ cần
-- một job quét định kỳ tồn tại chỉ để giữ cho cột đó khỏi sai.
ALTER TABLE app.family_invitations
    ADD CONSTRAINT ck_family_invitations_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED'));

ALTER TABLE app.family_invitations
    ADD CONSTRAINT ck_family_invitations_delivery_status
        CHECK (delivery_status IS NULL
               OR delivery_status IN ('SENT', 'FAILED', 'SKIPPED'));

CREATE INDEX ix_family_invitations_group_status
    ON app.family_invitations(family_group_id, status);
