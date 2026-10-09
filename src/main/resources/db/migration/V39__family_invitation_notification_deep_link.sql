-- Gỡ token thô ra khỏi deep link của những thông báo lời mời tạo trước nhánh trước.
--
-- Bản cũ ghi 'nutrimom://family/invitations/accept?token=<raw>' vào app.notifications.deep_link,
-- tức là cất một token dùng được trong DB dưới dạng chữ thường — vô hiệu hoá chính lý do tồn tại
-- của cột family_invitations.token_hash. Nhánh trước đã sửa đường sinh ra dòng mới, nhưng dòng cũ
-- vẫn nằm đó và GET /api/v1/notifications vẫn trả deep_link nguyên văn cho client.
--
-- Không xoá notification: người được mời vẫn phải thấy là họ từng được mời. Không log giá trị cũ.

-- Dựng lại đường dẫn theo id, đúng dạng mà nhóm endpoint /family-invitations/{id}/... dùng. Phân
-- quyền ở đó là email/sđt của tài khoản đang đăng nhập, không phải việc giữ một bí mật.
UPDATE app.notifications
   SET deep_link = 'nutrimom://family/invitations/' || source_id
 WHERE source_type = 'FAMILY_INVITATION'
   AND deep_link LIKE '%token=%'
   AND source_id IS NOT NULL;

-- Dòng không dựng lại được đường dẫn thì bỏ hẳn deep link. Thà mất một cú bấm còn hơn giữ lại một
-- token dùng được; tiêu đề và nội dung thông báo vẫn còn, và hộp thư lời mời tra được theo tài khoản.
UPDATE app.notifications
   SET deep_link = NULL
 WHERE source_type = 'FAMILY_INVITATION'
   AND deep_link LIKE '%token=%';
