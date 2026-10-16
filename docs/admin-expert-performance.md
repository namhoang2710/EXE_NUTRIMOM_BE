> **Phiên bản mới:** xem [Contract KPI v3](admin-expert-performance-v3-contract.vi.md): chuyên cần theo lịch làm việc, đúng giờ theo lần bấm vào phòng đầu tiên. Nội dung dưới đây mô tả v1; v1/v2 được giữ cho tương thích.

# KPI chuyên gia — hợp đồng BE cho Dashboard

Nhánh `feat/admin-operations-dashboard`. Chỉ BE; không sửa giao diện hoặc bật realtime FE. Migration V51 bổ sung tín hiệu thay đổi từ đánh giá và phòng video, tái sử dụng revision V50. V52 ghi thời điểm vào hàng chờ/tiếp nhận và thêm index/hàm deadline. Không sửa/backfill lịch sử chấm công, review, thời gian tiếp nhận hay trạng thái cộng tác.

## API

Swagger: **Admin expert performance**.

`GET /api/v1/admin/experts/performance`

Bearer access token có role ADMIN và tài khoản còn ACTIVE ADMIN trong DB. Cùng quota đọc Admin hiện tại; response `Cache-Control: no-store`. Không trả thông tin bệnh nhân, nhận xét, ghi chú lâm sàng hoặc token phòng.

| Query | Quy tắc |
| --- | --- |
| `fromDate`, `toDate` | Cặp ngày YYYY-MM-DD, bao gồm cả hai đầu; 1–366 ngày, không vượt hôm nay ở Việt Nam. Bỏ cả hai: đầu tháng đến hôm nay. |
| `specialty` | Bỏ trống để lấy tất cả; PSYCHOLOGY / OBSTETRICS / HEALTH, phân biệt hoa/thường. |
| `expertUserId` | UUID; bỏ trống để lấy cả đội. Không tồn tại hoặc không thuộc specialty đã chọn: 404. |
| `includeExtendedMetrics` | Boolean, mặc định false (4 trục tương thích FE hiện tại). true trả 6 trục, thêm Tiếp nhận đúng hạn và Khách quay lại. |

Lỗi auth 401/403 theo cơ chế Admin hiện có; ngày không parse được trả 400 theo handler hiện tại, validation nghiệp vụ/query sai trả 422; rate limit 429 kèm Retry-After. Query được bind parameter, không ghép giá trị người dùng vào SQL.

Một response trả cả radar và card tổng quan, không cần request từng trục. Không tải danh sách chuyên gia/lịch vào Java để tính phần trăm. Bộ chọn expert tái sử dụng API phân trang Experts hiện có, cache lựa chọn; không gọi lại danh sách khi chỉ đổi kỳ.

## Response

Envelope chuẩn `{data,meta}`. Field snake_case:

- `snapshot_at`, `timezone="Asia/Ho_Chi_Minh"`, `period` (gồm from/to và kỳ trước), `specialty`.
- `expert`: null khi xem đội; khi chọn người có `user_id, full_name, specialty, expert_status, account_status, profile_created_at`. Ngày tạo hồ sơ không phải ngày bắt đầu hợp đồng cộng tác.
- `rules`: `on_time_grace_minutes=0`, `satisfied_minimum_stars=4`, `cohort_basis=SCHEDULED_END_AT`, `attendance_evidence=COMPLETED_OR_VERIFIED_ROOM_JOIN`, `comparison_basis=PREVIOUS_EQUAL_LENGTH_PERIOD`.
- `axes`: bốn trục theo thứ tự ổn định ATTENDANCE, PUNCTUALITY, SATISFACTION, COMPLETION; nhãn tiếng Việt.
- Mỗi trục có `key,label,current,previous,change_percentage_points`.
- Mỗi value có `numerator,denominator,percent,status,missing_evidence`. `percent` là số 0–100 làm tròn hai chữ số, hoặc null khi không thể tính.
- `summary`: `total_experts,active_experts,eligible_consultations,completed_consultations,observed_consultations,rating_count,average_rating,departures_count,departures_status`.
- `realtime_revision`: chuỗi phiên bản Dashboard, không đổi thành Number ở FE.

Ví dụ một value: `{ "numerator": 18, "denominator": 20, "percent": 90.00, "status": "AVAILABLE", "missing_evidence": 0 }`.

`change_percentage_points` là hiệu hai tỷ lệ, không phải phần trăm tăng trưởng tương đối. Chỉ có giá trị khi cả hai kỳ AVAILABLE; một kỳ thiếu dữ liệu thì null. FE giữ nguyên null ở số liệu/tooltip. Nếu cần đủ vị trí bốn trục, dùng chấm rỗng ở tâm, đường nối nét đứt và nhãn — cho trục chưa có mẫu số; không gọi đó là 0%.

## Công thức và giới hạn dữ liệu

Kỳ dùng **ngày hẹn**, không dùng ngày tạo tài khoản/lịch hoặc ngày viết đánh giá. Kỳ trước là kỳ liền kề có cùng số ngày; mặc định 01–15/10 sẽ so với 16–30/09, không tự gọi đó là toàn tháng trước.

Lịch đủ điều kiện: đã có expert, giờ bắt đầu/kết thúc hợp lệ, thuộc ngày hẹn của kỳ và giờ kết thúc đã tới tại snapshot; status PENDING_CONSULTATION hoặc COMPLETED. Loại lịch hủy, chưa được phân công, chưa đến hạn và không có metadata lịch. Dùng slot thật hoặc snapshot V43 khi slot đã được giải phóng. Giờ kế thừa cùng quy ước lưu TIME và hiệu chỉnh đọc của Admin hiện tại; ngày/kỳ quy đổi Việt Nam.

| Trục | Tử số / mẫu số |
| --- | --- |
| ATTENDANCE — Chuyên cần | Lịch đã COMPLETED (completed_at không ở tương lai) hoặc có expert vào phòng hợp lệ không muộn hơn giờ kết thúc / tất cả lịch đủ điều kiện đã đến hạn. Mỗi lịch tính một lần. |
| PUNCTUALITY — Đúng giờ | Lịch đã COMPLETED với completed_at trước hoặc đúng giờ kết thúc đã hẹn / tất cả lịch đủ điều kiện đã đến hạn. Trễ 1 giây không đạt; quá giờ chưa hoàn tất không đạt. Vào phòng đúng giờ không bù cho kết thúc trễ. |
| SATISFACTION — Hài lòng | Đánh giá 4–5 sao / tổng đánh giá hợp lệ của các lịch hoàn thành đủ điều kiện; không tính đánh giá hoặc completion_at ở tương lai. |
| COMPLETION — Hoàn thành | Lịch COMPLETED có completed_at không vượt snapshot / tất cả lịch đủ điều kiện. Đây là trạng thái hoàn thành nghiệp vụ hiện có, không phải chứng nhận thời lượng tham gia. |

Bằng chứng phòng: có ít nhất một user/expert join đã được webhook LiveKit xác minh, nằm trong opens_at..closes_at và không ở tương lai. Chỉ tạo phòng/cấp token chưa đủ bằng chứng. `VideoSession` giữ lần join có thời điểm sớm nhất, kể cả webhook đến lệch thứ tự. Không suy diễn thời lượng hiện diện từ một lần join.

Theo yêu cầu cập nhật, chuyên cần là chỉ số thực hiện lịch trên tổng lịch đến hạn, không phải tỷ lệ trên tập có log video và không phải chấm công ngày làm việc. Lịch cũ đã hoàn thành vẫn được tính dù không có phòng video. Lịch đến hạn chưa hoàn thành và không có xác nhận expert tham gia không đạt chuyên cần theo quy tắc nghiệp vụ này; không trả null chỉ vì thiếu webhook. Không suy diễn nghỉ việc từ lịch vắng, tài khoản INACTIVE hoặc không có lịch.

Status: AVAILABLE / NO_ELIGIBLE_CONSULTATIONS / NO_REVIEWS. Mẫu số > 0 luôn có tỷ lệ, kể cả 0 thật. Không có lịch đến hạn thì ba trục lịch trả null; không có review thì satisfaction trả null. missing_evidence của ba trục lịch bằng 0; satisfaction là số lịch hoàn thành chưa có đánh giá. observed_consultations trong summary vẫn là số lịch có bằng chứng video, chỉ dùng chẩn đoán, không dùng làm mẫu số KPI.

completed_at là thời điểm expert thao tác hoàn thành nghiệp vụ, không phải đo thời điểm rời phòng bằng telemetry. Chỉ số đúng giờ tuân theo dấu mốc này; không tự sửa timestamp lịch sử để nâng KPI. Lịch hủy chưa phân biệt được ai hủy nên vẫn được loại khỏi cohort, không tự quy kết expert nghỉ.

Toàn đội cộng tử/mẫu số trước khi chia, không lấy trung bình các tỷ lệ của từng expert. Average rating là tổng sao / số đánh giá hợp lệ trong cùng cohort, không trung bình average_rating trên profile.

Summary total/active là snapshot đội ngũ **hiện tại**, lọc specialty theo hồ sơ hiện tại. Active đòi hỏi profile ACTIVE, account ACTIVE và role EXPERT. Giữ lịch sử KPI của profile INACTIVE. `departures_count=null`, `departures_status=NOT_TRACKED`: hệ thống chưa có lịch sử chấm dứt cộng tác, tuyệt đối không lấy INACTIVE làm số người nghỉ việc. FE hiển thị “Chưa có dữ liệu cộng tác”, không 0 người. Khi chọn một expert, card dùng trạng thái profile/account; không gọi profile_created_at là ngày bắt đầu cộng tác.

## Nhất quán, hiệu năng và realtime

Ba truy vấn aggregate/read cố định cho chế độ 4 trục, bốn cho chế độ 6 trục: tổng quan đội ngũ, hai kỳ KPI, revision. Kết quả trong transaction REPEATABLE_READ. Không N+1, giới hạn transaction/query KPI 10 giây qua Spring JDBC, không đọc lại Dashboard hoặc tăng query count của endpoint Dashboard cũ. Tái sử dụng các index lịch/expert/review hiện có, không thêm index trùng.

FE dùng một resource/cache key theo tài khoản + cặp ngày đã áp dụng + specialty + expert. Debounce bộ lọc, hủy/bỏ kết quả cũ, chỉ refetch một lần sau khi Apply. Fetch đồng thời với các thành phần Dashboard, không chờ animation.

V51 theo dõi INSERT/UPDATE/DELETE trên consultation_reviews và consultation_video_sessions qua collector V50: revision sau commit, rollback không phát tín hiệu, update không đổi dữ liệu không tăng revision. FE dùng stream Admin có sẵn; khi dashboard_revision đổi, đánh dấu cache KPI cũ và gom refetch resource đang hiển thị cùng cửa sổ realtime đã có. Không mở stream riêng cho chart; không reload trang hoặc gọi lại danh sách Users/Experts mỗi event.

Thời gian tự trôi qua không phát DB event: lịch vừa qua giờ kết thúc cần cơ chế hết hạn cache/focus/ngày Việt Nam hiện có. Không dùng polling nhanh để bù điều này. SSE vẫn tuân theo flag BE/FE riêng; endpoint KPI REST hoạt động ngay cả khi SSE tắt.

## Phạm vi xác minh

Test nghiệp vụ PostgreSQL dùng DB QA riêng với fixture giả; MockMvc kiểm tra quyền, validation và wire format. Không gọi LiveKit/PayOS thật hoặc sửa dữ liệu tài khoản dự án. Số đo test và các giới hạn triển khai được ghi trong `docs/admin-expert-performance-validation.md`.

## Hai chỉ số mở rộng — V52

Gọi `GET /api/v1/admin/experts/performance?includeExtendedMetrics=true` với cùng bộ lọc đã Apply. `axes` giữ bốn phần tử cũ rồi thêm:

| key | label | Tử số / mẫu số |
| --- | --- | --- |
| TIMELY_ACCEPTANCE | Tiếp nhận đúng hạn | Yêu cầu RANDOM được nhận trước/đúng deadline phục vụ / yêu cầu RANDOM đã được nhận hoặc đã hết deadline, trong tập có bằng chứng thời gian. |
| RETURNING_CUSTOMERS | Khách quay lại | Khách đã hoàn thành một buổi trong kỳ và trước lúc buổi đó bắt đầu đã hoàn thành một buổi với cùng expert / tổng khách có buổi hoàn thành đủ điều kiện trong kỳ. |

Mỗi chỉ số trả current/previous/percent/status/missing_evidence/change_percentage_points theo cấu trúc hiện có. Delta chỉ tính khi cả hai kỳ AVAILABLE.

### Tiếp nhận theo giờ phục vụ đã chốt

- Chỉ tính **08:00–20:00 hằng ngày, Asia/Ho_Chi_Minh**, đồng nhất SlotGrid hiện tại. Đây là khung phục vụ chung, chưa có lịch ngày lễ toàn đội. Không chạy đồng hồ 24/24.
- SLA mặc định 30 phút; cấu hình `NUTRIMOM_EXPERT_ACCEPTANCE_SLA_MINUTES` (1..1440). Quy tắc dùng khi đọc được trả trong rules; đổi SLA sẽ tính lại tỷ lệ lịch sử theo SLA đang cấu hình.
- 02:00 → hạn 08:30 cùng ngày; 19:50 → hạn 08:20 hôm sau; 19:30 → hạn 20:00 cùng ngày. Nhận chính xác deadline được tính đạt; vượt một giây là trễ. Expert tự nhận ngoài giờ trước deadline vẫn đạt.
- REQUEST mới RANDOM ghi acceptance_opened_at bằng Clock tại create, accepted_at tại accept, cùng transaction nghiệp vụ. Không dùng updated_at, không sửa hai timestamp khi hoàn thành/review và không đổi luồng claim/notification hiện có.
- DIRECT đang auto-book, không có thao tác expert tiếp nhận nên **loại khỏi KPI này**, không tự cho 100%.
- Tập kỳ của chỉ số này theo **ngày vào hàng chờ**; khác bốn trục tư vấn theo ngày hẹn. Nhận trước khi hết hạn đã đủ điều kiện tính; chưa nhận và vẫn trong hạn không vào mẫu số.
- Toàn đội tính mỗi request một lần, gồm pool chưa ai nhận đã hết hạn. Khi chọn expert, chỉ tính request đã thuộc expert đó; không quy request chưa ai nhận cho mọi cá nhân. Vì vậy tổng mẫu số cá nhân có thể nhỏ hơn tổng đội.
- Lọc chuyên khoa theo hồ sơ hiện tại cho request đã có expert; pool chưa phân công dùng chuyên khoa request. Cách này đồng nhất phạm vi expert với các trục cũ.
- CANCELLED được loại; dữ liệu cũ không có timestamp thật giữ nguyên null, không backfill từ updated_at hay ngày migration. Tập thiếu bằng chứng trả PARTIAL_DATA nếu còn mẫu tính được, hoặc NO_ACCEPTANCE_EVIDENCE khi chỉ có dữ liệu thiếu; missing_evidence là số request thiếu. Không có request đủ điều kiện trả NO_ELIGIBLE_REQUESTS/null. Không biến việc thiếu dữ liệu thành đánh giá vắng/chậm cá nhân.

### Khách quay lại

Tận dụng lịch sử completed_at/expert_user_id/user_id. Chỉ lịch COMPLETED có completed_at không ở tương lai, trước giờ bắt đầu buổi sau mới là bằng chứng quay lại. Loại lịch hủy/chưa hoàn tất; khách từng khám expert khác không phải khách quay lại của expert đang xem. Không dùng chính buổi hiện tại làm lịch sử kể cả timestamp cũ bị bất thường.

Đếm mỗi người một lần trong kỳ; xem đội gộp theo user, người đến nhiều expert vẫn chỉ đếm một lần nếu có ít nhất một cặp quay lại hợp lệ. Xem cá nhân vẫn một user một lần. Có thể quay lại trong cùng kỳ hoặc từ kỳ trước; truy vấn lịch sử vượt kỳ lọc khi tìm buổi cũ. Đây là tín hiệu hành vi, không thay thế đo chất lượng tư vấn.

Không có khách có buổi hoàn thành trả NO_COMPLETED_CUSTOMERS/null, có khách nhưng chưa ai quay lại trả 0/AVAILABLE. Tổng đội đếm distinct user, không cộng trực tiếp số khách của từng expert vì có thể trùng người.

### Metadata và tối ưu

Rules thêm `acceptance_sla_minutes`, `acceptance_cohort_basis=QUEUE_OPENED_AT`, `acceptance_scope=RANDOM_QUEUE_TEAM_OR_CLAIMED_EXPERT`, `returning_customer_basis=SAME_EXPERT_COMPLETED_BEFORE_VISIT_START`, `acceptance_opening_time=08:00`, `acceptance_closing_time=20:00`, `acceptance_timezone=Asia/Ho_Chi_Minh`.

Một truy vấn bổ sung trả cả hai chỉ số/kỳ. first_completed được materialize một lần để không tái aggregate lịch sử cho mỗi dòng lịch; index (expert,user,completed_at) hỗ trợ lịch sử, index ngày hàng chờ hỗ trợ cohort. Hàm deadline cộng thời gian phục vụ O(1), không generate_series theo từng phút/ngày. Không thêm SSE/job; mutation consultation_requests đã được V50 theo dõi. Thời gian trôi qua qua deadline dùng cache/focus/fallback hiện có, không tự phát sự kiện khi không có mutation.

### Bàn giao FE

Hiện FE mapper chốt 4 trục. **Không bật includeExtendedMetrics=true trước khi cập nhật mapper/chart.** FE mới gọi cùng endpoint, bật flag, chấp nhận 6 key và status mới, giữ null/coverage/delta đúng nghĩa, bố trí nhãn 6 hướng theo góc thực tế (không dùng layout tick cố định 4 hướng). Dùng rules để giải thích 30 phút trong giờ phục vụ, không ghi “trễ sau 30 phút liên tục”. Không gọi từng API cho từng trục.

Báo cáo hiệu suất là phần tích hợp sau; lượt này không thêm report/export hay route sidebar mới. Khi bổ sung phải dùng chung công thức/cohort này.
