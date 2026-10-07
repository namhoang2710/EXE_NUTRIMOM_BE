-- Tra lời mời theo địa chỉ người được mời, cho hộp thư "lời mời gửi cho tôi".
--
-- Không thêm cột invited_user_id. Lời mời được tạo cho một địa chỉ, không cho một tài khoản:
-- người nhận có thể đăng ký SAU khi lời mời được gửi, hoặc đổi email sau đó. Một cột đóng băng
-- lúc tạo sẽ lệch với điều kiện mà accept kiểm (so khớp email/sđt sống của tài khoản), và cho ra
-- đúng thứ tệ nhất: lời mời hiện trong hộp thư nhưng bấm vào thì bị từ chối.

-- invited_email đã được chuẩn hoá lowercase ở tầng ứng dụng từ trước; câu này dọn những dòng
-- được tạo trước đó để phép so sánh bằng luôn đúng. Idempotent, không mất dữ liệu.
UPDATE app.family_invitations SET invited_email = lower(invited_email)
 WHERE invited_email IS NOT NULL AND invited_email <> lower(invited_email);

-- Index thường, không phải index biểu thức lower(): dữ liệu đã chuẩn hoá nên so sánh bằng là đủ,
-- và index thường thì khai báo lại được trong @Table(indexes = ...) để H2 trong test cũng có.
CREATE INDEX ix_family_invitations_invited_email
    ON app.family_invitations(invited_email);
CREATE INDEX ix_family_invitations_invited_phone
    ON app.family_invitations(invited_phone);
