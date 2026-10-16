# Kết quả kiểm tra thông báo Admin

Ngày: 14/10/2026. Nhánh: `feat/admin-operations-dashboard`.

## Kết quả

| Kiểm tra | Kết quả |
|---|---|
| Lint 21 file Java liên quan bằng `javac -encoding UTF-8 -Xlint:all -Werror -proc:none` | PASS, không có warning/error |
| `git diff --check` | PASS |
| Maven verify cho nhóm Admin/notification/payment và API liên quan | 102 test được thống kê: 101 PASS, 1 test CMS có @Disabled từ trước; 0 failure/error |
| Build JAR Spring Boot qua verify trên | BUILD SUCCESS |
| PostgreSQL QA: thông báo, webhook, concurrent delivery và migration | 25/25 PASS, không skip |
| Migration Flyway từ DB trống tới V47, dữ liệu cũ rồi V48; migrate lại | PASS, giữ read_at, phân biệt Admin/cá nhân, không phát lịch sử; migrate lại không tạo thay đổi |
| Toàn bộ repository, hai lần chạy | Chưa PASS: 495 test thống kê, 10 failure, 215 error, 14 skip; xem giới hạn môi trường bên dưới |

Không bổ sung skip để làm xanh bộ test. Test CMS đã bị disable trước task này có lý do Windows loopback selector, nằm trong `AdminKnowledgeMockMvcRegressionTest.actualHttpMultipartEnforcesServletLimitsWithRealJwt`.

## Phạm vi test mới

`AdminNotificationIntegrationTest`: 16 scenario, chạy trên cả H2 và PostgreSQL:

- Queue cùng transaction; rollback không phát thông báo; record trùng và dispatcher replay không tạo bản sao hoặc reset trạng thái đã đọc.
- Fan-out tới hai ACTIVE ADMIN, không tới USER/EXPERT/ADMIN bị vô hiệu hóa; trạng thái đọc của mỗi Admin độc lập và đọc chéo owner trả 404.
- Thu hồi role trong DB chặn audience ADMIN với JWT cũ, kể cả list/count/read/read-all; request không truyền filter không làm lộ hàng Admin.
- Filter category/audience, lỗi filter/cursor/limit, request chưa đăng nhập; tách notification cá nhân của Admin; keyset pagination ổn định khi insert mới.
- Đăng ký mật khẩu/OTP/magic link, kích hoạt email, tự vô hiệu hóa; đăng nhập và xác thực thất bại không tạo sự kiện mới.
- Mua gói/gia hạn, xác nhận lặp; hỗ trợ mới/hoàn tất/hủy; lịch DIRECT/RANDOM/tiếp nhận/hoàn tất/hủy; không lộ nội dung hỗ trợ/ghi chú y tế.
- Ba worker cùng chạy; một event lỗi được retry riêng, không chặn event sau; sửa DB trực tiếp không tạo event.

`AdminNotificationSchedulingTest`: 2 scenario cho mặc định bật và cấu hình tắt scheduler.

`AdminNotificationMigrationPostgresTest`: 1 scenario cho chuỗi Flyway đầy đủ và dữ liệu cũ, chỉ chạy với cờ opt-in. Test khóa URL vào database QA riêng `127.0.0.1:55439/admin_notification_flyway`; chỉ clean schema app của database disposable này trước/sau test. Không cho trỏ tới DB thật.

`PaymentSafetyIntegrationTest` được bổ sung assert webhook REAL gửi lặp đồng thời chỉ tạo một outbox cho đơn hàng; 7 scenario thanh toán hiện có tiếp tục PASS trên PostgreSQL. Producer thông báo cũ và dashboard/query Admin cũng nằm trong nhóm hồi quy.

## Giới hạn bộ test toàn repository

Đã chạy Maven verify toàn repo, rồi chạy lại với quyền loopback và tăng context cache. Cả hai lần vẫn không đạt. Các lỗi khởi tạo `GroqAssistantClient`/`LiveKitClient` và HTTP fake server có root cause `Unable to establish loopback connection` / `SocketException: Permission denied` hoặc `Invalid argument: connect` trên Windows. Test context khởi tạo thất bại còn gây lỗi H2 `USERS not found` cho các context dùng chung database, và một số test nhận 500 thay vì response kỳ vọng.

Đã chạy riêng 10 test HTTP client với tùy chọn IPv4 để kiểm chứng; 9 vẫn lỗi loopback. Không thay đổi Groq/LiveKit production code, không tắt các test này. Do đó **không kết luận toàn repository xanh**, dù các test nghiệp vụ bị tác động bởi thay đổi thông báo và build đã qua. Cần môi trường Java có localhost selector hoạt động để chạy lại toàn bộ suite.

## Lệnh kiểm thử và bằng chứng

Nhóm hồi quy đã PASS và tạo JAR:

```powershell
.\mvnw.cmd '-Dtest=AdminNotificationIntegrationTest,AdminNotificationSchedulingTest,NotificationIntegrationTest,NotificationProducerIntegrationTest,ConsultationRequestServiceTest,AdminAuthIntegrationTest,AdminOperationsIntegrationTest,AdminUserIntegrationTest,AdminDashboardRedesignIntegrationTest,AdminMetricConcurrencyIntegrationTest,AdminNotificationConcurrencyIntegrationTest,PaymentIntegrationTest,PaymentSafetyIntegrationTest,AdminMetricsSchedulingTest,AdminKnowledgeMockMvcRegressionTest' '-DargLine=-javaagent:target/test-tools/mockito-agent.jar' verify
```

Lượt PostgreSQL cuối cùng dùng `admin_qa` ở localhost, `ddl-auto=validate`, Flyway tắt trong Spring test context; migration test riêng bật bằng `-Dadmin.notification.migration.test=true`. Các class: `AdminNotificationIntegrationTest`, `AdminNotificationMigrationPostgresTest`, `PaymentSafetyIntegrationTest`, `AdminNotificationConcurrencyIntegrationTest`. Scheduler tắt trong test profile, dispatcher được gọi chủ động.

Log local trong target (không đưa log/fixture vào Git):

- `admin-notification-lint.log`: lint cuối cùng không có output lỗi.
- `admin-notification-scoped-verify.log`: 101 PASS, 1 skip có sẵn, BUILD SUCCESS.
- `admin-notification-final-postgres-tests.log`: PostgreSQL/migration cuối cùng; 25 PASS theo Surefire XML.
- `admin-notification-verify.log`, `admin-notification-verify-2.log`: kết quả toàn repo chưa đạt.
- `admin-notification-loopback-tests.log`: lỗi localhost của test HTTP client.

Chỉ dùng DB QA localhost; không tạo giao dịch, gửi email, push thật hoặc áp dụng V48 trên DB đang phục vụ người dùng. PostgreSQL QA được dừng sau khi kiểm thử.

BE chưa commit/push/deploy. Xem [hợp đồng API và phần FE cần nối](admin-notifications.md). FE cũ hiển thị nội dung qua API cũ; filter nhóm, refresh feed theo count và deep link tài khoản/thanh toán cần nối thêm ở FE.
