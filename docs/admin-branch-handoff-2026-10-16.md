# Bàn giao nhánh BE Admin — 16/10/2026

Nhánh: `feat/admin-operations-dashboard`. Phạm vi commit gồm toàn bộ BE Admin đã làm trong nhánh; FE được quản lý ở repository riêng.

## Nội dung

- Dashboard, khách hàng, chuyên gia, doanh thu/gói, giao dịch và báo cáo preview/CSV; giới hạn phân trang, xuất dữ liệu và truy vấn tổng hợp.
- Thông báo Admin theo audience/category, outbox bền vững, chống trùng, cursor, đọc từng mục/đọc tất cả và deep link.
- SSE Admin có feature flag, revision sau commit, heartbeat, quota, kiểm tra quyền hiện tại và hết hạn token.
- KPI v3: chuyên cần từ lịch làm việc; đúng giờ từ lần expert bấm vào phòng đầu tiên theo giờ server; hài lòng, hoàn thành, tiếp nhận đúng hạn và khách quay lại.
- Mốc dữ liệu do DB trả, lọc theo kỳ/khoa/expert, chặn ngày trước mốc, không so xu hướng khi kỳ bị thiếu thời gian dữ liệu. Báo cáo và dashboard dùng cùng công thức.
- Hạn tiếp nhận RANDOM theo giờ Việt Nam, thông báo cho user/expert và tác vụ quá hạn cho Admin.
- Sửa buổi gần nhất của lịch expert: giữ danh sách theo giờ hẹn giảm dần, nhưng chọn buổi đang diễn ra/sắp tới theo giờ hẹn tăng dần trên toàn phạm vi lọc, độc lập trang hiện tại.

## Contract bổ sung cho lịch expert

`GET /api/v1/expert/consultation-requests?type=assigned&includeNext=true`

Response giữ `items`, `page`, `page_size`, `total_items`, `total_pages`; bổ sung `next_consultation` và `server_time`. Không phát sinh request HTTP thứ hai cho banner. Chỉ tính buổi `PENDING_CONSULTATION` có lịch hẹn, chưa hết giờ; áp dụng cùng `from`, `to`, `q`. Với lịch sử, pool hoặc không còn buổi phù hợp, không có buổi gần nhất để hiển thị. `includeNext` mặc định `false`.

## Migration và cấu hình

- Commit phải mang đầy đủ V42–V56. Không triển khai code mới với schema cũ.
- V55/V56 dành cho lịch làm việc và receipt check-in; sửa coverage và buổi gần nhất không thêm migration.
- Tại lúc fetch để chuẩn bị push, `origin/main` đã có V57 của module dinh dưỡng; không trùng số với V42–V56.
- SSE mặc định tắt: `NUTRIMOM_ADMIN_REALTIME_ENABLED=false`. Bật theo cấu hình triển khai khi endpoint/proxy đã sẵn sàng.
- Outbox Admin và consultation monitor mặc định bật. KPI v3 đúng giờ không yêu cầu bật LiveKit presence tracking.
- `.env`, token/credentials, `target/` và log QA không nằm trong commit. `.env.example` chỉ mô tả cấu hình.

## Tái hiện kiểm thử trên Windows

Chạy QA trên source copy không có `.env`, JDK 17. Trên máy này selector của JDK cần thư mục Unix-domain socket ngắn; cấu hình `java.io.tmpdir` một mình không giải quyết được lỗi. Tạo thư mục ngắn đã tồn tại rồi truyền các tùy chọn sau cho JVM test:

```text
-Djdk.net.unixdomain.tmpdir=D:\KI8\.tmp\jsock
-Djava.net.preferIPv4Stack=true
```

Mockito agent được lấy từ dependency `mockito-core` của dự án và đặt trong thư mục QA `target/test-tools/mockito-agent.jar`:

```powershell
.\mvnw.cmd '-DargLine=-javaagent:target/test-tools/mockito-agent.jar -Djdk.net.unixdomain.tmpdir=D:\KI8\.tmp\jsock -Djava.net.preferIPv4Stack=true' verify
```

Các test PostgreSQL yêu cầu bật cờ opt-in và chỉ dùng DB QA localhost port 55439 có URL guard. Không trỏ những bài migration/fixture này vào DB online. Kiểm tra mặc định không tự bật thanh toán PayOS thật.

Các tài liệu validation trước đây ghi kết quả tại từng thời điểm thực hiện. Kết quả chuẩn bị push mới nhất được ghi dưới đây, không thay thế lịch sử các lần kiểm tra đó.

## Kết quả chuẩn bị push

- Maven `verify` toàn repository: 604 test được ghi nhận, 529 thực thi đạt, 0 failure, 0 error, 75 skip theo cờ/cấu hình. JAR được build thành công.
- Bật riêng toàn bộ 72 test PostgreSQL/migration/performance/SSE HTTP trong số skip trên: 72 đạt, 0 failure, 0 error, 0 skip.
- Tổng hợp hai lượt: 601 test đạt, 3 skip còn lại: `PrivateR2LiveStorageTest`, `PayOSLiveTest` và bản override HTTP multipart của `AdminKnowledgeMockMvcRegressionTest`. HTTP multipart gốc trong `KnowledgeCmsIntegrationTest` đã thực thi đạt.
- Lint `javac -Xlint:all -Werror -proc:none -encoding UTF-8`: đạt trên 74 file production và 50 file test thay đổi.
- Sau khi thay helper deprecated, chạy lại 110 test liên quan: 109 đạt, 0 failure, 0 error, 1 skip là override multipart trùng đã nói trên; `verify`/JAR tiếp tục đạt. Đây là lượt lặp, không cộng thêm vào tổng 601.
- Kiểm tra staged diff và mẫu credentials: không phát hiện file môi trường riêng, private key, token hay lỗi whitespace trong phần chuẩn bị commit.
- PostgreSQL QA: dashboard với 10.000 user/lịch/đơn có p95 90 ms trên 30 mẫu, tối đa 20 logical read calls; đây là số đo QA, không phải cam kết production.
- Luồng SQL commit → outbox → SSE HTTP: 1.385 ms, có kiểm tra count/revision và thu hồi quyền đóng stream. Không còn bị chặn bởi lỗi socket Windows sau khi cấu hình JDK như trên.
- Sửa test migration V14 dùng DB H2 riêng cho từng invocation, tránh xung đột giữa suite CMS gốc và suite kế thừa. Các helper deprecated trong test được thay bằng kiểm tra tương đương, không giảm assertion.

Log local ngoài commit: `D:\KI8\.tmp\be-prepush-verify-final.log`, `be-prepush-pg-final.log`, `be-prepush-cleanup-regression.log`, `be-prepush-lint.log`, `be-prepush-test-lint.log`.

Đợt chuẩn bị push chỉ dùng bản sao QA không có `.env` và PostgreSQL QA; không chạy migration hoặc ghi dữ liệu vào DB online, không restart BE đang chạy. Chưa triển khai cloud hay thanh toán PayOS thật.
