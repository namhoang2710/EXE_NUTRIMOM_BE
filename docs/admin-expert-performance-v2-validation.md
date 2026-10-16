# BE KPI v2 — Báo cáo kiểm chứng 15/10/2026

Nhánh `feat/admin-operations-dashboard`. Chỉ triển khai BE; không sửa FE, không push/deploy và không ghi DB online trong lượt này.

Contract: [KPI chuyên gia, hạn tiếp nhận và thông báo v2](admin-expert-performance-v2-contract.vi.md).

## Kết quả

- **120 test liên quan: 0 lỗi, 0 thất bại, 0 bỏ qua**, tổng hợp các suite hồi quy và kiểm tra bổ sung cuối cùng. Đây không phải tuyên bố đã chạy toàn bộ test của repository.
- Java 17 lint: `-Xlint:all -Werror`, UTF-8, không tắt cảnh báo: **pass** trên 23 file nguồn của thay đổi.
- Đóng gói BE sau thay đổi cuối: **pass**.
- Chuỗi Flyway và nâng cấp đến V53, chạy lại không phát sinh migration, bảo toàn dữ liệu cũ: **pass**.
- Native PostgreSQL: 10.000 lịch, 20 lần đo, KPI 6 trục v2 **p95 245 ms** ở lượt cuối, giữ ngân sách p95 dưới 1 giây.
- Snapshot v2 5 lượt đọc cố định; báo cáo có dữ liệu 8 lượt cố định, có kiểm tra số query; lấy tên User bằng projection để tránh load role từng người.
- Kiểm tra diff/whitespace, UTF-8, JSON minh họa contract: **pass**.

## Phạm vi đã kiểm tra

- Lần vào phòng đầu tại -5 phút, đúng giờ, +3 phút, +3 phút 1 giây và +15 phút; hoàn tất không xóa việc vào trễ.
- Phòng đã kết thúc vẫn nhận bằng chứng webhook đến chậm với thời gian xảy ra hợp lệ; không mở lại phòng. Replay/out-of-order giữ lần vào sớm nhất và sửa cùng một thông báo.
- Lịch cũ hoàn thành nhưng thiếu log không được tự chấm chuyên cần/đúng giờ. Khoảng gián đoạn chỉ ảnh hưởng lịch giao với khoảng đó; không xóa độ phủ của ngày khác.
- Các giá trị 1–5 sao đều tham gia mẫu số/điểm trung bình; chỉ 4–5 vào tử số hài lòng.
- Ranh giới deadline 07:59:59, 08:00, 18:59:59, 19:00, 19:50, 23:59:59, 02:00, qua năm; SQL và Java cùng kết quả; đúng 08:30 không bị coi quá hạn.
- Ngày mong muốn không đổi deadline, không giữ trước slot; chặn ngoài horizon/quá khứ và accept đổi sang ngày khác.
- Ngày bắt đầu dữ liệu, kỳ mặc định được giới hạn, kỳ trước không đủ phạm vi không có chênh lệch giả.
- Thông báo quá hạn idempotent; bỏ qua đã nhận/đã hủy, không phát lịch sử hàng loạt.
- Hai worker đồng thời chỉ tạo một sự kiện; booking bị luồng khác khóa được bỏ qua và thử lại, không chờ khóa kéo dài.
- Hết giờ hẹn phát tín hiệu KPI; sau cửa sổ chờ bằng chứng hoàn tất theo dõi; không quét lại toàn bộ lịch sử đã xử lý.
- Lịch tương lai đã đặt trước nâng cấp được chuẩn hóa từ dữ liệu lịch thật, không tự tạo log vào phòng.
- Phân quyền Admin bằng JWT và trạng thái tài khoản; bộ lọc chuyên khoa/expert, phân trang, giới hạn CSV, escape formula; bảo toàn các báo cáo cũ.
- Hồi quy đặt/nhận/hủy/hoàn thành, cạnh tranh đặt slot và thông báo đúng chuyên khoa.

## Môi trường và giới hạn thực tế

PostgreSQL 16 riêng `127.0.0.1:55439/admin_qa`; migration test dùng DB `admin_notification_flyway` có guard URL trước clean. API integration dùng H2 và mock dịch vụ ngoài. Không chạy migration vào DB .env/online và không thay dữ liệu người dùng thật.
LiveKit được mô phỏng trong test. Khi triển khai phải smoke test webhook có chữ ký, rồi bật `NUTRIMOM_VIDEO_PRESENCE_TRACKING_ENABLED=true` để được phép kết luận vắng khi không có join. Mặc định false giữ các trường hợp đó là chưa rõ; deadline/thông báo vẫn hoạt động.
FE đang dùng contract cũ sẽ chưa thể hiện đủ tính năng mới. Bước FE tiếp theo cần `calculationVersion=2&includeExtendedMetrics=true`; không fallback v1 dưới nhãn v2.

## Suite đã qua

| Suite | Test |
|---|---:|
| AdminExpertPerformanceV2PostgresTest | 16 |
| AdminExpertPerformancePostgresTest | 18 |
| AdminNotificationMigrationPostgresTest | 1 |
| ConsultationVideoIntegrationTest | 11 |
| ConsultationIntegrationTest | 22 |
| ConsultationConcurrencyTest | 2 |
| ConsultationRequestServiceTest | 5 |
| ConsultationTimingTest | 2 |
| AdminExpertPerformanceIntegrationTest | 9 |
| AdminOperationsIntegrationTest | 10 |
| AdminNotificationIntegrationTest | 16 |
| NotificationProducerIntegrationTest | 6 |
| ConsultationMonitoringConcurrencyPostgresTest | 2 |

## Bằng chứng cục bộ

- `target/kpi-v2-final-tests.log`: hồi quy chính.
- `target/kpi-v2-double-check.log`: kiểm tra thêm webhook/CSV và native SQL.
- `target/kpi-v2-monitor-final.log`: worker, tín hiệu thời gian và migration V53 cuối.
- `target/kpi-v2-upgrade-final.log`: lịch tương lai cũ và video.
- `target/kpi-v2-ready.log`: native test + package bản nguồn cuối.
- `target/kpi-v2-lint.log` rỗng khi thành công, `target/kpi-v2-lint-sources.txt` liệt kê nguồn đã lint.
- `target/kpi-v2-validation-results.json`: tổng hợp XML test.
