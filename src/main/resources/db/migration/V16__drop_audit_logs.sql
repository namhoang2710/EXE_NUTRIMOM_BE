SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

-- Gỡ bảng audit_logs (V15). Task 21 chỉ giữ rate-limit + row-level; audit chưa cần cho phạm vi hiện tại.
-- Dùng migration drop thay vì xoá V15 để không phá Flyway trên DB đã áp dụng V15.
DROP TABLE IF EXISTS app.audit_logs;
GO
