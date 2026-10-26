-- Vòng đời đơn thanh toán: hạn hiệu lực của link checkout và dấu vết đối soát của job nền.
--
-- Không backfill expires_at. Đơn tạo trước migration này được gửi sang PayOS không kèm expiredAt
-- nên link của chúng không tự hết hạn; job đối soát coi expires_at NULL là
-- created_at + checkout-ttl và sẽ gọi cancel lên PayOS trước khi đánh EXPIRED, nếu không link cũ
-- còn thanh toán được mãi.
--
-- ck_payment_orders_status của V31 đã liệt kê 'EXPIRED' nên không cần sửa ràng buộc.
-- Mọi câu lệnh ở đây và trong job đều KHÔNG liệt kê source hay payos_payment_link_id, nên
-- trg_payment_source_legacy_writer (V45) không chạy.

ALTER TABLE app.payment_orders
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE app.payment_orders
    ADD COLUMN IF NOT EXISTS reconcile_attempt_at TIMESTAMP WITH TIME ZONE NULL;

-- Hàng đợi của job đối soát. Index duy nhất đang có là (user_id, created_at DESC), không phục vụ
-- được câu quét theo hạn nên job sẽ seq-scan cả bảng mỗi 5 phút. Partial index giữ cho nó chỉ to
-- bằng số đơn đang chờ thật sự. Hai cột vì câu quét có hai nhánh: expires_at cho đơn mới,
-- created_at cho đơn cũ chưa có hạn.
CREATE INDEX IF NOT EXISTS ix_payment_orders_pending_expiry
    ON app.payment_orders (expires_at, created_at)
    WHERE status = 'PENDING' AND source = 'REAL';
