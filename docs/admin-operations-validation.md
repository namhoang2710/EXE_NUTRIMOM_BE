# Admin BE — kết quả xác minh 13/10/2026

Nhánh: `feat/admin-operations-dashboard`. Đã fetch và fast-forward tới `origin/main` tại `27a9071`, sau đó khôi phục toàn bộ phần Admin đang làm. Không có conflict hoặc migration trùng tên. Không commit/push/deploy phần Admin.

Lần cập nhật main tiếp theo đã fast-forward tới `0b5bb84` (password policy). Cả 54 file thay đổi cục bộ được đối chiếu SHA-256 trước/sau và giữ nguyên, không có conflict. Build `-DskipTests package` thành công sau cập nhật; các số liệu kiểm thử dưới đây thuộc lượt trước tại `27a9071`, chưa chạy lại suite sau cập nhật password policy.

## Dashboard redesign — xác minh bổ sung chiều 13/10/2026

- FE thực sự gọi một GET dashboard qua read coordinator. Trạng thái “Dữ liệu chưa khả dụng” trước sửa là do response BE chưa có `overview`, `customer_snapshot`, `monthly_revenue`, `plan_399k_adoption`.
- Đã thêm bốn nhóm vào endpoint hiện có, giữ phần vận hành cũ, không tăng số request FE. Thêm range ALL_TIME cho Revenue summary/Payment orders để link từ KPI tổng doanh thu hoạt động đúng.
- Payload do integration test BE sinh được kiểm tra bằng chính `mapDashboardRedesign`/`mapRevenueSummary` trong repo FE; cả dữ liệu thiếu lịch sử lẫn lịch sử có mẫu so sánh đều hợp lệ.
- Đã kiểm tra HTTP với BE local đang chạy trên cổng 8080: response dashboard được mapper FE chấp nhận đủ bốn nhóm và ALL_TIME revenue hợp lệ. Không lưu hoặc in credential/token; phiên kiểm tra riêng được thu hồi sau khi đọc.
- Migration V44 đã chạy trên PostgreSQL QA; JPA validate khớp schema. Kiểm thử snapshot hiện tại, biên hết hạn, ngày thiếu, chốt tháng Việt Nam và upsert cùng giờ giữ quan sát mới nhất. Capture chạy READ_COMMITTED: một aggregate query cho số đếm, rồi atomic upsert; tránh serialization failure khi nhiều instance cùng chụp. GET dashboard vẫn REPEATABLE_READ và không ghi ledger.
- Đã chạy lại 24 suites regression liên quan trên H2, không failure/error; riêng HTTP multipart thật của CMS vẫn skip vì giới hạn loopback nêu dưới. Thêm kiểm thử transaction capture đồng thời trên PostgreSQL; hai case concurrency đều qua. Tập suites PostgreSQL gồm dashboard, scheduler, snapshots, export quota, consultation và payment safety không failure/error.
- Benchmark dashboard đầy đủ mới: 10.000 requests + 10.000 orders, 30 samples, 18 logical read calls, p95 168 ms. Ngưỡng test được siết xuống tối đa 20 calls; số đo không gồm mạng/JWT guard và tải đồng thời.
- Build BE `-DskipTests package` và FE `npm run build` đều thành công. 8 tests FE redesign đều qua. FE không bị sửa source; build còn warning kích thước chunk LiveKit video 650 kB, nằm ở runtime video riêng.
- Lịch sử 399K bắt đầu từ triển khai; response thực hiện tại là INSUFFICIENT_HISTORY. Không có dữ liệu giả để đổi chart đỏ/xanh trước khi đủ mẫu.

Log mới: `target/admin-redesign-regression.log`, `target/admin-redesign-postgres.log`, `target/admin-redesign-capture-postgres.log`, `target/admin-redesign-package.log`; FE build log nằm trong `qa/admin-workspace/dashboard-api-build.log` (artifact QA).

## Stats cards 21st.dev — kiểm tra bổ sung 13/10/2026

- FE đã thay bốn KPI bằng bố cục Stats10 gọn, thêm mini area chart/gradient cho cả bốn. Chart tiền thu và khách mới có nhãn kỳ đã chọn; tổng chính vẫn là tổng lũy kế. Mốc 399K duy nhất hiện dot, không nối qua ngày thiếu hoặc dựng dữ liệu giả.
- BE bổ sung 24 bucket lịch theo giờ trong cùng response, không thêm HTTP request. Native query Admin đọc LocalTime nhất quán với JPA/User/Expert, kể cả timezone JVM; không thay dữ liệu lịch đã lưu.
- 19 integration cases H2 và cùng 19 cases trên PostgreSQL đều passed; thêm 1 benchmark PostgreSQL passed: 19 logical read calls, p95 296 ms / 30 samples với 10.000 requests + 10.000 orders. Số đo service không gồm mạng/JWT guard/tải đồng thời.
- FE 9 tests passed, build FE và package BE thành công. Visual QA dùng chính component production trong preview riêng, đã kiểm tra desktop 1440, mobile 390 không tràn ngang và dark theme; fixture được ghi rõ dữ liệu minh họa.
- Browser QA login bị timeout nên chưa xác nhận toàn dashboard có auth qua browser trong lượt này. Kiểm tra component bằng preview không auth; API/contract/SQL được integration test kiểm tra riêng.
- Log: `target/admin-stats-hourly.log`, `target/admin-stats-postgres.log`, `target/admin-stats-package.log`; FE `qa/admin-workspace/stats-cards-tests.log`, `stats-cards-build.log`, `stats-21st-desktop.jpg`, `stats-21st-mobile.jpg`.

## Kết quả đợt triển khai ban đầu

| Nhóm | Kết quả |
|---|---|
| 21 suites H2, gồm Admin và entitlements mới từ main | 146 cases: 144 passed, 0 failed/error, 2 skipped; gồm lượt tổng hợp và lượt bổ sung validate Expert |
| 7 suites PostgreSQL 16.14 | 23 cases passed, 0 failed/error/skipped |
| Build Maven `-DskipTests package` | Thành công |
| Migrations PostgreSQL | V1..V43 chạy thành công; JPA validate khớp schema |
| V42 backfill dữ liệu cũ | MOCK -> DEMO, provider -> REAL, thiếu bằng chứng -> LEGACY_UNKNOWN; dedup giữ trạng thái đã đọc, không xóa notification của User |
| V43 backfill dữ liệu cũ | Giữ đủ ngày/giờ/id của slot còn liên kết; không bịa thời gian lịch cũ mất slot; constraint từ chối snapshot thiếu trường |
| Dashboard service PostgreSQL | 10.000 requests + 10.000 orders, warmup 3, đo 30 lượt: p95 190 ms, 14 logical read calls |

Các database thử nghiệm được tạo riêng tại loopback `127.0.0.1:55439`: `admin_qa`, `admin_qa_backfill`, `admin_qa_schedule`. Benchmark và business fixtures PostgreSQL được rollback hoặc cleanup. Không dùng database trong `.env` của dự án. Số đo service không bao gồm HTTP/TLS, mạng, JWT guard hoặc tải đồng thời; không phải cam kết p95 production.

Đã kiểm tra quyền JWT + trạng thái/role hiện tại trong DB, token cũ bị thu hồi quyền, encoded URI, các giới hạn query/UUID/orderCode/ngày, literal wildcard, tiền thật/demo/unknown và biên ngày Việt Nam. Đã kiểm tra CSV chống formula injection, cap 10.000 trước khi gửi file, quota export, lịch hủy giữ snapshot và notification Admin đồng thời/độc lập trạng thái đọc.

Thanh toán đã kiểm tra owner của confirm-mock, demo tắt mặc định, nguồn đơn, trạng thái terminal, amount/currency của webhook, đồng thời xác nhận/webhook không gia hạn trùng và hai đơn khác nhau gia hạn đủ. Provider verification được mock; không giao dịch PayOS thật.

Expert đã kiểm tra tài khoản không còn ADMIN không ghi được dữ liệu, mật khẩu 30 ký tự nhưng quá 72 byte UTF-8 bị từ chối trước insert, PATCH tên trắng/version thiếu hoặc âm bị chặn, version cũ trả 409 và không ghi đè cập nhật mới. Bộ kiểm thử Users, Consultation, Support, Notifications, Calendar, Knowledge và entitlement giữ các assertions nghiệp vụ hiện có.

## Giới hạn kiểm thử

- Test multipart HTTP thật của Knowledge cần Tomcat listening socket. Java NIO loopback bị môi trường Windows hiện tại từ chối, nên dùng `AdminKnowledgeMockMvcRegressionTest` chạy 13 case CMS qua MockMvc; riêng case HTTP được skip rõ ràng. Test HTTP gốc vẫn giữ nguyên.
- Một case Consultation cũ tự skip do assumption cần chạy sau 08:30 giờ Việt Nam; lượt chạy này ở đầu ngày. Case quá giờ của Admin được kiểm tra riêng bằng Clock cố định.
- Groq/LiveKit được mock trong các suites này vì không liên quan đến nghiệp vụ Admin và khởi tạo HTTP client bị giới hạn loopback. Không kiểm tra gọi nhà cung cấp video/AI thật.
- Đã chạy các suites liên quan, không tuyên bố toàn bộ suite của repository đều chạy.

## Chạy lại

Mockito agent được nạp tường minh để tránh dynamic attach bị chặn trên Windows. Jar dùng bản cùng dependency Mockito của dự án, đặt trong `target/test-tools/mockito-agent.jar` (file generated/ignored).

```powershell
.\mvnw.cmd -q '-DargLine=-javaagent:target/test-tools/mockito-agent.jar' '-Dtest=AdminAuthIntegrationTest,AdminOperationsIntegrationTest,AdminUserIntegrationTest,AdminExportRateLimitIntegrationTest,AdminNotificationConcurrencyIntegrationTest,AdminConsultationIntegrationTest,ConsultationIntegrationTest,ContactRequestIntegrationTest,NotificationIntegrationTest,NotificationProducerIntegrationTest,PaymentIntegrationTest,PaymentSafetyIntegrationTest,CalendarEventsIntegrationTest,RateLimitFilterTest,RateLimitRouteCoverageTest,ImageOptimizationServiceTest,AdminKnowledgeMockMvcRegressionTest,EntitlementServiceTest,PlanCatalogTest,SubscriptionPlanIntegrationTest,EntitlementEnforcementIntegrationTest' test
.\mvnw.cmd -q -DskipTests package
```

Benchmark opt-in bằng `-Dadmin.performance.test=true`. Test này chặn datasource khác đúng database QA nêu trên và không nên chạy vào database thật. Giữ `ddl-auto=validate` khi chạy suites PostgreSQL trên schema đã migration. Log và kết quả chi tiết của lượt kiểm tra nằm trong `target/admin-*-validation*.log`, `target/admin-postgres-final.log`, `target/admin-*-validation-results.json` và Surefire reports; chúng không được commit.
