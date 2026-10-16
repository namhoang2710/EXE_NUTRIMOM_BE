# Kiểm chứng BE KPI chuyên gia — mở rộng 15/10/2026

Nhánh `feat/admin-operations-dashboard`. Thêm Tiếp nhận đúng hạn và Khách quay lại, kiểm tra bộ lọc KPI. Hợp đồng/FE handoff: `docs/admin-expert-performance.md`.

## Kết quả cuối

- Maven verify/JAR: **89/89 pass**, không failure/error/skip. KPI permission/filter/wire 7 test; regression Admin operations/Dashboard, consultation create/accept/complete/review, video, realtime và thông báo.
- PostgreSQL QA: **19/19 pass**, không skip: 18 KPI business/SQL/snapshot/benchmark + 1 chuỗi migration từ dữ liệu cũ. Flyway V52 chạy thành công, lần chạy lại không có migration mới; request cũ vẫn null timestamp.
- 6 trục: p95 **90 ms** trên 10.000 lịch, 20 mẫu, cố định **4 truy vấn**. 4 trục cũ: p95 **29 ms**, 3 truy vấn. Budget 1 giây được giữ nguyên. Số đo QA, không phải cam kết production.
- Java compiler lint trên package Admin và hai class consultation đã sửa: pass với UTF-8, Xlint:all, Werror. git diff --check: pass.
- Chrome FE QA: **13/13 pass**, dùng transport fixture, không sửa source UI. Có Apply specialty chỉ một GET, response600ms kết thúc skeleton và đổi dữ liệu, lỗi500 dừng loading/không retry loop, các ca chọn kỳ/expert/404/429/login/reduced motion cũ.

## Quy tắc đã kiểm tra

- 02:00 không bắt đầu đếm SLA trước 08:00; 19:50 cộng tiếp tới 08:20 hôm sau. Đúng deadline đạt, trễ 1 giây không đạt. Giữ phần giây nhỏ, ranh giới 20:00, qua năm, SLA dài qua nhiều ngày. Quy tắc chốt 08:00–20:00 hằng ngày giờ Việt Nam, không có lịch lễ riêng.
- Timestamps ghi tại create RANDOM và accept bằng Clock, cùng transaction. complete/review không đổi. DIRECT không tự trở thành tiếp nhận nhanh. Không dùng updated_at hoặc backfill timestamp lịch sử.
- Pool chưa hết hạn không vào mẫu số, đã hết hạn chưa nhận giảm tỷ lệ đội. Chưa được phân công không quy lỗi cho từng expert. Per-expert chỉ có các request thuộc người đó, khác phạm vi toàn đội.
- Legacy missing trả PARTIAL_DATA hoặc NO_ACCEPTANCE_EVIDENCE; không có cohort trả NO_ELIGIBLE_REQUESTS. Delta null nếu một kỳ chưa đầy đủ. Không biến missing thành 0%.
- Returning cần cùng expert và buổi trước đã hoàn thành trước khi buổi sau bắt đầu; bỏ lịch hủy/chưa hoàn tất/future. Lặp nhiều buổi vẫn chỉ một người; nhóm toàn đội dedup user. Không tính chính buổi hiện tại là lịch sử dù completed_at bất thường.
- Bộ lọc API từ/ngày, đến/ngày, specialty, expert và flag tới đúng SQL. Fixture DB cho kết quả khác giữa cả đội/từng expert/chuyên khoa/từng ngày và kỳ không dữ liệu. Specialty follow profile hiện tại cho claimed requests; pool dùng specialty request.
- Default API vẫn 4 trục để mapper FE hiện tại không lỗi; includeExtendedMetrics=true mới trả 6. Token ADMIN + ACTIVE ADMIN trong DB, invalid query/scope bị chặn, response không chứa thông tin bệnh nhân.

## Tối ưu đã sửa

Benchmark phát hiện PostgreSQL có thể inline và tính lại aggregate first_completed nhiều lần khi nhiều lịch trùng user/expert. Đã materialize tập lịch sử một lần, thêm index (expert,user,completed_at), giữ query count cố định. Sau sửa đạt số đo trên; benchmark extended có statement_timeout5s riêng để phát hiện regression, không nâng ngưỡng để che lỗi.

Endpoint có transaction/query timeout10s qua Spring JDBC. Timeout này không thay thế FE timeout hay bảo đảm toàn bộ HTTP dưới10s khi chờ pool/mạng. FE API client hiện có timeout15s và 429 cooldown. Không tăng polling hay thêm stream/job.

## Điều chưa kết luận

Không tái hiện vòng xoay kéo dài trong QA. Test bộ lọc BE và Chrome cho thấy tham số, kết quả và loading đúng; kiểm tra local8080 không có token chỉ nhận401 nhanh, không đủ để kết luận trạng thái phiên thật. Không khẳng định đã sửa nguyên nhân spinner trong môi trường đang dùng. Cần đối chiếu request Network/BE log của phiên đó nếu còn xảy ra sau khi dùng đúng bản BE/schema.

## Môi trường, phạm vi

JDK17, PostgreSQL16 riêng `127.0.0.1:55439/admin_qa`. Migration test dùng `admin_notification_flyway` riêng với exact-URL guard trước clean. Fixture rollback/cleanup, không dùng DB .env hoặc chỉnh tài khoản thật. MockMvc test quyền/wire với repository mock; PostgreSQL test native SQL/service thật; Chrome dùng API fixture. Không gọi PayOS/LiveKit thật, không phải toàn bộ test repository, không deploy/push hoặc đổi .env thật.

V52 cần đi cùng BE mới, dùng Flyway hiện có khi restart/deploy. FE6 cần cập nhật mapper/status/nhãn6hướng và gọi includeExtendedMetrics=true; chưa thay UI4trục trong lượt BE này. Báo cáo hiệu suất/sidebar là phần tích hợp sau.

Logs: `target/admin-extended-kpi-verify.log`, `target/admin-extended-kpi-postgres.log`, `target/admin-extended-kpi-lint.log`. FE: `fe-performance-browser.log`, kết quả `qa/admin-workspace/expert-performance/results.json`.
