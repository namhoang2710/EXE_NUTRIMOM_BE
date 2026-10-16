# Kết quả kiểm chứng KPI v3

Ngày: 15/10/2026. Nhánh hiện tại: `feat/admin-operations-dashboard`.

## Phạm vi đã thực hiện

- Chuyên cần từ lịch làm việc: ngày nghỉ, giờ đóng, ưu tiên BOOKED, không trừ trùng; đủ 28/29/30/31 ngày.
- Tổng hợp theo khung giờ chuẩn của từng expert, một khoa hoặc toàn đội; expert mới vào giữa tháng không mang mẫu số cả tháng.
- Đúng giờ từ thao tác bấm vào phòng đầu tiên bằng giờ server, độ phân giải microsecond của PostgreSQL; không dùng bằng chứng LiveKit hay hoàn thành để suy ra.
- API check-in riêng và tích hợp tương thích vào API join hiện có; receipt không bị rollback khi provider video lỗi.
- Cập nhật thông báo đến trễ, revision khi lịch/receipt thay đổi và khi hết 3 phút cho phép.
- Dashboard v3, báo cáo preview/CSV v3, giữ v1/v2 để FE hiện tại không nhận công thức mới ngoài ý muốn.
- Migration V55/V56 bổ sung bảng receipt, mốc bắt đầu tracking và worklist xử lý theo batch.

## Kiểm tra đã qua

**67 tests, 0 failures, 0 errors, 0 skipped** trong các bộ liên quan:

| Bộ kiểm thử | Số test |
| --- | ---: |
| AdminExpertPerformanceIntegrationTest | 11 |
| AdminExpertPerformanceV2PostgresTest | 16 |
| AdminExpertPerformanceV3PostgresTest | 9 |
| AdminKpiV54MigrationPostgresTest | 2 |
| ConsultationMonitoringConcurrencyPostgresTest | 2 |
| ExpertKpiPeriodTest | 2 |
| RateLimitRouteCoverageTest | 6 |
| ExpertCheckinServiceTest | 4 |
| ConsultationVideoIntegrationTest | 14 |
| AdminNotificationMigrationPostgresTest | 1 |

Các tình huống trọng tâm:

- Tháng 10 có ngày 31; tháng 2 thường 28 ngày, năm nhuận 29 ngày; chuyển năm; so tháng trước có số ngày khác nhau.
- Ngày nghỉ trùng giờ CLOSED không bị trừ hai lần; BOOKED vẫn là giờ làm việc.
- Một khoa và toàn đội dùng trọng số theo mẫu số, không lấy trung bình các khoa.
- Các ranh giới −5 phút, +3 phút, +3 phút 1 microsecond, +15 phút; hoàn thành không biến trễ thành đúng giờ.
- Lịch sử không có receipt được trả unknown; buổi mới không click được trả not_checked_in sau hạn.
- Xem trước phòng không tạo receipt; user/anonymous không được dùng endpoint check-in expert.
- Hai lần check-in đồng thời tạo đúng một receipt; retry trả timestamp đầu tiên.
- Video provider lỗi vẫn giữ receipt đã commit bằng transaction riêng.
- Job quá hạn idempotent, có khóa SKIP LOCKED; webhook không ghi đè thông báo đã có receipt.
- Preview/report CSV dùng chung công thức, scope, phân trang, escape công thức spreadsheet.
- 10.000 cuộc tư vấn: dashboard giữ 6 truy vấn aggregate/revision, nằm trong ngân sách kiểm thử 3 giây; báo cáo giữ 10 truy vấn không phụ thuộc số expert trong trang.
- Upgrade từ hai hình dạng V53, từ chuỗi V47→V56, chạy lại migration không đổi dữ liệu cũ hay tạo receipt giả.

**Lint:** `javac -Xlint:all -Werror -proc:none -encoding UTF-8` trên 10 file Java production và 9 file test được thêm/sửa trong lượt này: PASS.
Repository không có cấu hình Checkstyle/Spotless; đây là kiểm tra warning Java nghiêm ngặt.
**Build:** Maven `package -DskipTests`: PASS; 67 tests được chạy riêng trước đó.
**Whitespace:** `git diff --check`: PASS.

## Môi trường và giới hạn xác nhận

Kiểm thử được chạy trên bản sao source tại `D:\KI8\.tmp\expert-kpi-be-qa`, Java 17, PostgreSQL 16 QA localhost port 55439 và H2 cho HTTP/security/video.
Không copy `.env`, không migrate/chỉnh dữ liệu DB online, không restart tiến trình BE của người dùng.
Kết quả performance là ngân sách trên fixture QA, không phải cam kết latency của hạ tầng production.
Đây là bộ hồi quy liên quan, không phải chạy toàn bộ mọi test của repository.

Khi dùng bản BE mới phải để Flyway chạy V55 và V56. FE bước sau cần chọn `calculationVersion=3` ở dashboard và báo cáo,
đồng thời đọc `schedule_summary`/`checkin_summary`; lượt này chưa sửa FE.

Contract và ví dụ công thức: [admin-expert-performance-v3-contract.vi.md](admin-expert-performance-v3-contract.vi.md).
