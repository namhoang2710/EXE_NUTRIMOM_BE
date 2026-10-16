# Kiểm chứng BE Admin Realtime — 15/10/2026

Nhánh `feat/admin-operations-dashboard`. Chỉ thay BE và tài liệu/test; không sửa runtime FE, không commit/push/deploy hoặc cập nhật dữ liệu tài khoản thật.

## Phạm vi

- Endpoint SSE, feature flag, quota/capacity, heartbeat, JWT expiry/current-role guard, cleanup và bounded sender.
- Migration V50: revision sau commit, bắt cả SQL, thông báo thay đổi gói, chống trùng API và không ghi thêm doanh thu khi sửa thời hạn.
- Metadata `realtime_revision` trên Dashboard và thông báo ADMIN; giữ response USER và constructor CursorPage cũ.
- Outbox 1 giây, clock SSE riêng, không query khi không có subscriber. Gauge kết nối và log lỗi không chứa token.

Hợp đồng chi tiết: `docs/admin-realtime.md`. Wire format khớp tài liệu task FE đã tách trước đó.

## Kết quả

- Java lint: `javac -encoding UTF-8 -Xlint:all -Werror` cho toàn package Admin và các lớp thay đổi liên quan: **pass**.
- `git diff --check`: **pass**.
- Hồi quy H2/unit/Servlet, Maven verify: **131 test; 130 pass, 0 failure, 0 error, 1 skip**. Đã build executable JAR thành công.
- Skip thuộc bài CMS HTTP multipart đã có từ trước do môi trường localhost Windows. Không thêm skip để che lỗi SSE.
- Hồi quy PostgreSQL, migration và benchmark: **45/45 pass, 0 failure, 0 error, 0 skip**; lượt cuối kết thúc 11:16:01, log `target/admin-realtime-postgres-final.log`.
- Pipeline SQL commit → outbox → SSE qua Servlet: **1.471 ms**, JWT/outbox/thu hồi quyền pass. Đây là số đo QA, không phải cam kết độ trễ production/browser.
- Dashboard service với 10.000 khách hàng, 10.000 yêu cầu tư vấn và 10.000 đơn: **p95 78 ms / 30 mẫu**, 20 logical read calls; giữ nguyên giới hạn cũ dưới 1 giây và tối đa 20 calls.

Các test mới kiểm tra một SELECT cho các subscriber cùng tick, idle không query, không gửi duplicate snapshot, account isolation, token expiry/revocation, caps, queued-send bound, queue saturation, DB failure, đăng ký connection giữa lúc poll và flag off.

MockMvc kiểm tra chính SseEmitter/JSON converter: ready/update có snake_case không envelope, lỗi 401/403/422/429 đúng JSON, Retry-After và async completion không bị chặn lại hoặc mở quyền ASYNC chung.

PostgreSQL dùng đúng `127.0.0.1:55439/admin_qa`; migration test dùng DB riêng `admin_notification_flyway` có URL guard trước clean. Fixture không phải người dùng thật.

Test pipeline Servlet sử dụng JWT được ký thật, PostgreSQL commit, outbox scheduler và SSE sender thực. Không gọi dispatch/tick thủ công trong bài này. Test quan sát count và revision từ SQL grant, đối chiếu API count, sau đó thu hồi role và nhận access_revoked. Đây là kiểm tra Servlet streaming, không phải browser/proxy hoặc socket HTTP production.

## Các vấn đề phát hiện và đã xử lý khi kiểm thử

- Revision query ban đầu làm tăng số lời gọi logical trong benchmark quá ngưỡng cũ. Đã dùng trực tiếp row-mapper query; không nâng ngưỡng 20 để che regression.
- Fixture cũ tắt/bật trigger bằng ALTER TABLE trong transaction gặp pending deferred trigger events của V50. Test đã flush constraints trước fixture-only DDL và khôi phục DEFERRED sau đó; không đổi timing production.
- Sự kiện teardown fixture có thể sót sang bài dispatcher tiếp theo. Cleanup giới hạn theo user fixture; đồng thời thêm assertion trước teardown chứng minh PaymentService không sinh thông báo SQL trùng. Không giảm assertion số thông báo của nghiệp vụ.
- Poll chạy song song với open có thể nhầm connection mới là mất quyền nếu không nằm trong batch DB vừa đọc. Hub đã chụp batch, bảo đảm ready gửi trước update và có regression test.
- Benchmark ban đầu dùng DB QA rỗng, seed 10.000 dòng trong transaction chưa commit nên autovacuum không thấy fixture. Planner dùng cardinality gần 0 và chọn join chậm. Đã dừng lượt đó (log `target/admin-realtime-postgres-cancelled-benchmark.log`) rồi thêm ANALYZE các bảng fixture trước warm-up. Không nâng ngưỡng 1 giây/20 lời gọi, không thay truy vấn nghiệp vụ để điều chỉnh kết quả test.

## Lệnh chạy

Java được dùng: Microsoft JDK 17 tại `C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot`.

Kiểm tra phần realtime không cần DB ngoài:

```powershell
.\mvnw.cmd '-Dtest=AdminRealtimeConfigurationTest,AdminRealtimeHubTest,AdminRealtimeIntegrationTest' '-DargLine=-javaagent:target/test-tools/mockito-agent.jar' test
```

Các test PostgreSQL phải opt-in, dùng DB QA đã migrate V50. Tham số kết nối bắt buộc:

```text
-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55439/admin_qa
-Dspring.datasource.username=adminqa
-Dspring.datasource.password=
-Dspring.datasource.driver-class-name=org.postgresql.Driver
-Dspring.jpa.hibernate.ddl-auto=validate
-Dspring.flyway.enabled=false
```

Chạy `AdminRealtimePostgresTest,AdminRealtimeScheduledPostgresTest` với `-Dadmin.audit.postgres.test=true`. Lượt hồi quy còn chạy benchmark, revenue snapshot concurrency, notification producers, payment safety/source compatibility, subscription recognition và Flyway V48→V50/idempotence.

## Giới hạn cần giữ rõ khi bàn giao

Đã thử `AdminRealtimeHttpPostgresTest` với `-Dadmin.realtime.http.test=true` và IPv4, nhưng embedded Tomcat vẫn không khởi động được: `Unable to establish loopback connection` / `SocketException: Invalid argument: connect`. Test opt-in vẫn giữ để chạy trên CI/staging có socket hoạt động; không coi lượt này là pass.

Đợt này không chạy lại toàn bộ repository sau khi audit trước đã xác nhận nhiều lỗi HTTP/LiveKit/Groq tương tự trên Windows. Các kết quả pass ở trên là những suite được ghi rõ, không phải chứng nhận toàn bộ BE xanh.

Chưa thử browser FE thật, proxy/CDN hoặc thanh toán PayOS thật trong lượt này. Cần xác nhận allowed origin nếu FE khác origin (cấu hình hiện tại chỉ cho localhost/127.0.0.1), buffering/timeout, refresh token và reconnect qua đường deploy trước khi bật cờ. Không mở CORS wildcard để bỏ qua kiểm tra này.

Mặc định BE vẫn `NUTRIMOM_ADMIN_REALTIME_ENABLED=false`. FE có thể triển khai adapter/test trước nhưng chỉ bật sau khi BE đã migrate, bật flag và smoke test. Migration luôn phải đi cùng JAR mới, kể cả khi transport SSE còn tắt.

Log chính:

- `target/admin-realtime-final-verification.log`
- `target/admin-realtime-postgres-final.log`
- `target/admin-realtime-lint.log`
- `target/admin-realtime-http.log` — bằng chứng giới hạn socket, không phải kết quả pass.
