# Gói 399K hiển thị nhưng doanh thu bằng 0 — 13/10/2026

## Nguyên nhân và đối soát

DB có ba đơn PAID 399.000đ với source LEGACY_UNKNOWN. Dashboard/Revenue mặc định chỉ tính REAL + PAID + paid_at hợp lệ, còn cơ cấu gói tính subscription hiện tại. Vì vậy gói hiện nhưng doanh thu bằng 0.

Đơn của Nam Hoang có payment link PayOS. Đã đối soát GET bằng merchant credentials hiện có: PayOS PAID, amount và amountPaid đều 399000, orderCode và link ID khớp DB. Sau đối soát, sửa duy nhất source UNKNOWN -> REAL và tăng version; không sửa số tiền, trạng thái, paid_at hoặc gói. Không tạo giao dịch tiền mới.

Kết quả HTTP local sau sửa:

- overview.lifetime_real_collected_amount = 399000.
- monthly_revenue tháng 2026-10 = 399000.
- Revenue summary source REAL = 399000.
- Hai đơn còn lại không có link PayOS vẫn UNKNOWN; không cộng nhầm tổng 1197000 của ALL nguồn vào doanh thu thật.

## Hiệu lực gói

- Nam Hoang: start 13/10, end 12/11, đúng 30 ngày. UI báo 29 ngày còn lại vì Duration.toDays lấy phần ngày tròn.
- Tainzo và Nguyễn Mickey: DB lưu start 13/10, end 11/04/2027, tổng 180 ngày. UI báo 179 ngày còn lại cùng nguyên tắc trên. Không có link PayOS, PAID ngay thời điểm tạo; chưa có bằng chứng thanh toán thật cho hai đơn này.
- PaymentService và PlanCatalog hiện cấp 30 ngày/lần; gia hạn hợp lệ cộng 30 ngày lên gói còn hiệu lực. Chủ dự án xác nhận hai gói Tainzo và Nguyễn Mickey được cấp trực tiếp trong PostgreSQL để test và yêu cầu giữ nguyên 180 ngày.

## Bản sửa lâu dài

Code checkout trong nhánh này đã ghi nguồn REAL/DEMO tường minh. Các writer cũ hoặc luồng ghi chưa có source vẫn có thể để DB default UNKNOWN sau khi V42 đã backfill lần đầu.

V45 bổ sung trigger và catch-up cho link MOCK hoặc PayOS 32-hex, giữ UNKNOWN khi chưa có link. Không bỏ bộ lọc tiền thật của dashboard, không gọi PayOS trong GET Admin, không dựng paid_at hoặc tạo/gia hạn subscription. Migration nằm trong nhánh chưa push; lượt xử lý DB ứng dụng chỉ sửa đúng đơn đã đối soát, chưa triển khai migration V45 trên DB đó.

QA PostgreSQL riêng kiểm tra old writer, link bổ sung sau insert, nguồn tường minh không bị đổi, thiếu/sai link không được tính thật, backfill idempotent, timestamps không thay đổi, không tạo subscription. Thêm regression thanh toán REAL 399K cấp đúng 30 ngày. Log: target/payment-source-compatibility-postgres-final.log và target/payment-source-compatibility-package.log.

Không lưu credentials, token, QR/link thanh toán hoặc chi tiết giao dịch ngân hàng trong tài liệu/QA artifacts.


## Quy tắc mới sau xác nhận của chủ dự án

Chủ dự án yêu cầu cộng chung các lần lên gói bằng PayOS và team dev set trong PostgreSQL, giữ tên Total revenue, không phân nhóm hiển thị. Vì vậy kết luận loại hai gói 180 ngày khỏi tổng ở phần đối soát phía trên chỉ mô tả quy tắc cũ. Với quy tắc mới, ba đơn PAID hiện có (một REAL, hai LEGACY_UNKNOWN) được tính tổng 1.197.000đ. Không đổi nguồn của hai đơn test hoặc sửa thời hạn 180 ngày.

V46 bổ sung trigger cho các lượt lên gói trực tiếp trong DB sau này. Đơn PAID đã có là ledger được dùng luôn; trigger/backfill không tạo thêm cho trường hợp đã được ghi nhận. Frontend và backend dùng ALL cho tổng, tháng và kỳ thống kê. Các khóa API chứa real trong tên giữ để tương thích, không còn giới hạn REAL trong dashboard.


Đã cập nhật BE local cổng 8080 từ nhánh feat/admin-operations-dashboard và áp dụng migration ghi nhận lên gói trong DB ứng dụng. Kiểm tra HTTP phiên Admin riêng: Total revenue = 1197000; Revenue Overview tháng 2026-10 = 1197000; summary ALL = 1197000; current399KUsers = 3. Không đổi nguồn hai đơn UNKNOWN, không tạo thêm đơn cho ba gói đã có PAID. Phiên QA được đăng xuất.

V47 giữ mã đơn tự ghi nhận trong phạm vi integer chính xác của JavaScript. Các bản build/kiểm thử mới đều PASS; chưa commit/push hoặc deploy cloud. Trên Windows, launcher local dùng thư mục socket Java ngắn trong target/jdk-sockets để tránh lỗi Unix-domain socket ở thư mục TEMP của môi trường công cụ. Đây là tùy chọn local, không thay đổi code Assistant.
