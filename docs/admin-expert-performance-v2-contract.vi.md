# Contract BE v2 — KPI chuyên gia, hạn tiếp nhận và thông báo

> Chuyên cần và Đúng giờ đã có [contract v3](admin-expert-performance-v3-contract.vi.md) theo lịch làm việc và thao tác vào phòng. V2 bên dưới giữ nguyên để tương thích client cũ; FE mới phải chọn `calculationVersion=3`.

Ngày cập nhật: 15/10/2026. Nhánh: `feat/admin-operations-dashboard`.

Đây là contract cho FE làm tiếp. Những thay đổi dưới đây nằm ở BE; FE hiện tại chưa chuyển sang v2. Không có dữ liệu mẫu được ghi vào DB online trong quá trình phát triển/kiểm thử.

## 1. Những yêu cầu đã triển khai ở BE

1. Chuyên cần dựa vào sự có mặt của expert trong lịch đã được phân công; không dùng nút hoàn thành thay bằng chứng có mặt.
2. Đúng giờ dựa vào **lần kết nối vào phòng đầu tiên do webhook LiveKit xác thực**: từ trước giờ hẹn 5 phút đến sau giờ hẹn 3 phút, bao gồm đúng mốc +3 phút.
3. Hoàn thành vẫn dựa trên trạng thái hoàn tất của lịch; một buổi có thể vừa hoàn thành vừa bị trễ.
4. Hài lòng tính đánh giá 4–5 sao / toàn bộ đánh giá 1–5 sao. Đánh giá 1–3 không bị bỏ: chúng nằm ở mẫu số và điểm trung bình.
5. Giữ phép tính khách quay lại cùng chuyên gia; thêm vào contract 6 trục.
6. Trả số buổi vắng, trễ và chưa rõ bằng chứng; hồ sơ được chọn vẫn có trạng thái expert/tài khoản, số hoàn thành, điểm trung bình.
7. Đọc ngày hẹn hợp lệ sớm nhất từ **DB mà BE đang kết nối**, không hardcode 16/09 hay ngày tạo DB. Mặc định kỳ này không bắt đầu trước dữ liệu; bộ lọc ngày tường minh sai mốc trả 422.
8. So sánh với khoảng ngay trước, cùng số ngày; trả cả tử/mẫu của hai kỳ. Không có bằng chứng hay kỳ trước thiếu phạm vi dữ liệu thì không tạo số chênh lệch giả.
9. Lịch RANDOM có hạn nhận lưu trong DB và phiên bản chính sách; mốc 19:00 chuyển sang hạn 08:30 hôm sau.
10. RANDOM nhận `preferred_date` (ngày mong muốn). Ngày này không phải đặt trước giờ trống và không thay đổi hạn nhận.
11. Thông báo cho User về hạn nhận; thông báo đúng chuyên khoa cho Expert; cảnh báo vào phòng sau 3 phút; thông báo Admin và mục cần xử lý khi quá hạn nhận.
12. Tác vụ nền xử lý theo lô, khóa cùng dòng booking với accept/cancel/webhook; chống gửi trùng khi chạy lại/nhiều máy.
13. Báo cáo hiệu suất có preview và CSV, dùng chung cohort/cách tính với dashboard, giới hạn xuất 10.000 chuyên gia.
14. Danh sách lịch User/Expert phân trang trong DB; các tên, slot, trạng thái đã đánh giá được lấy theo lô, không gọi một lượt cho từng dòng.

## 2. Endpoint, quyền và chuyển phiên bản

Swagger **Admin expert performance**:

```http
GET /api/v1/admin/experts/performance?calculationVersion=2&includeExtendedMetrics=true
GET /api/v1/admin/experts/performance?calculationVersion=2&includeExtendedMetrics=true&fromDate=2026-10-01&toDate=2026-10-15&specialty=HEALTH&expertUserId=<UUID>
```

- ADMIN JWT và tài khoản Admin hiện còn hoạt động trong DB.
- `fromDate` và `toDate` cùng có hoặc cùng không; 1–366 ngày, không vượt hôm nay theo Việt Nam.
- `specialty`: `HEALTH`, `PSYCHOLOGY`, `OBSTETRICS`; không truyền để lấy tất cả.
- `expertUserId`: UUID canonical, phải nằm trong chuyên khoa đã chọn; không tìm thấy trả 404.
- `includeExtendedMetrics=true`: đủ 6 trục. Không truyền vẫn có 4 trục.
- `calculationVersion=1` hoặc không truyền: nhánh tương thích FE cũ; **không phải logic mới**. Phải chuyển FE sang 2 để dùng định nghĩa chuyên cần/đúng giờ đã sửa. Không được lấy v1 làm fallback rồi ghi nhãn theo v2.
- Snapshot trong một transaction repeatable-read. Dữ liệu tổng hợp không lộ ghi chú khám, token phòng hoặc đánh giá văn bản.

Các field mới trong `data` (snake_case):

```json
{
  "calculation_version": 2,
  "data_coverage": {
    "earliest_scheduled_date": "2026-09-01",
    "tracking_started_at": null,
    "previous_period_complete": true,
    "comparison_status": "COMPLETE"
  },
  "rules": {
    "on_time_grace_minutes": 3,
    "attendance_evidence": "VERIFIED_FIRST_ROOM_JOIN",
    "acceptance_sla_minutes": 30,
    "acceptance_opening_time": "08:00",
    "acceptance_closing_time": "19:00"
  },
  "summary": {
    "absent_consultations": 0,
    "late_consultations": 0,
    "unknown_attendance_consultations": 0
  }
}
```

Đây là **trích field minh họa**, không phải số liệu sản xuất hoặc toàn bộ response. `acceptance_closing_time=19:00` là mốc chuyển chính sách nhận lịch, không thay giờ tư vấn 08:00–20:00.

`tracking_started_at=null` khi chưa bật/xác nhận theo dõi. `previous_period_complete` chỉ xác nhận **phạm vi ngày**; phải xét `axes[].previous.status` và `current.status` để biết từng KPI có đủ bằng chứng hay không.

## 3. Mốc đo và kỳ so sánh

Cohort chuyên cần/đúng giờ/hài lòng/hoàn thành/khách quay lại dùng **ngày hẹn** và chỉ tính lịch được phân công còn trạng thái `PENDING_CONSULTATION` hoặc `COMPLETED`, có khung giờ hợp lệ, đã đến giờ kết thúc tại `snapshot_at`.

Không bắt đầu đo từ lúc expert nhận lịch. Ví dụ: nhận lịch ngày 01/09 cho buổi ngày 15/10 thì KPI buổi đó nằm ở ngày 15/10.

Kỳ 01–15/10 có 15 ngày. Kỳ trước là 16–30/09, cũng 15 ngày, được tính bằng cách lùi đúng độ dài kỳ. **16/09 không phải ngày hệ thống bắt đầu đo**. Nếu chọn 01–07/10, kỳ trước là 24–30/09.

Ngày nhỏ nhất của bộ lọc lấy từ lịch có ngày/khung giờ hợp lệ và expert trong DB runtime. Khi DB chưa có lịch, `earliest_scheduled_date=null`; không dựng một mốc giả.

Mỗi trục có:

```text
current: numerator / denominator × 100, status, missing_evidence
previous: numerator / denominator × 100, status, missing_evidence
change_percentage_points = current.percent − previous.percent
```

Ví dụ kỳ trước có 4/4 đúng điều kiện → 100%; kỳ này 25/28 → 89,29%. Chênh lệch là **−10,71 điểm phần trăm**, không phải tự trừ một chuẩn mặc định 100%. Kỳ trước có 2/4 → 50% cũng là số đếm từ DB.

Chỉ trả chênh lệch khi cả hai giá trị `AVAILABLE` và kỳ trước nằm trong phạm vi ngày có dữ liệu. Thiếu dữ liệu trả `null`, không trả 0 hay mặc định 100%.

## 4. Sáu KPI và các trường hợp dễ nhầm

| Trục | Tử số | Mẫu số |
|---|---|---|
| ATTENDANCE — Chuyên cần | Lịch có expert vào phòng được xác thực trong cửa sổ phòng | Lịch đến hạn xác định được sự có mặt/vắng mặt |
| PUNCTUALITY — Đúng giờ | Lịch có lần vào đầu từ start−5 phút đến start+3 phút | Cùng mẫu số chuyên cần |
| SATISFACTION — Hài lòng | Đánh giá 4 hoặc 5 sao | Tất cả đánh giá 1–5 sao của lịch hoàn thành trong cohort |
| COMPLETION — Hoàn thành | Lịch đã hoàn thành với completed_at không vượt snapshot | Tất cả lịch hợp lệ đến hạn trong cohort |
| TIMELY_ACCEPTANCE — Tiếp nhận đúng hạn | RANDOM được nhận không muộn hơn deadline lưu trong DB | RANDOM đã được nhận hoặc đã qua deadline, có đủ timestamp đáng tin cậy |
| RETURNING_CUSTOMERS — Khách quay lại | Khách có một buổi cùng expert đã hoàn thành trước lúc bắt đầu buổi hiện tại | Khách distinct có buổi hoàn thành trong kỳ |

### Có mặt, đúng giờ, vắng và chưa rõ

- Ví dụ hẹn 08:00–08:30: phòng mở 07:55. Vào 07:55, 08:00 hoặc 08:03:00 đều đúng giờ. 08:03:01 trở đi là trễ.
- Vào 08:15, hoàn thành 08:30: **có mặt + hoàn thành + trễ**. Không biến thành đúng giờ vì bấm hoàn tất.
- Chuyên cần không phải quản lý chấm công cả ngày/ca HR. Ngày không có lịch được giao không bị xem là nghỉ.
- Buổi đã được book vẫn là nghĩa vụ khi expert bật nghỉ ngày đó; nghỉ ngày chỉ ngăn nhận thêm slot mới.
- Chỉ cấp token, mở màn hình hoặc bấm hoàn thành không chứng minh đã kết nối phòng.
- Giữ lần vào sớm nhất đã xác thực khi reconnect/webhook gửi lại hoặc đến sai thứ tự.
- Cửa sổ ghi nhận mặc định từ start−5 đến trước end+5 phút. Không có join chỉ được xác định vắng sau end+6 phút: 5 phút đóng phòng và 1 phút chờ webhook, với độ bao phủ theo dõi liên tục.
- Không có join và không có độ bao phủ theo dõi đáng tin cậy → **chưa rõ**, không phải vắng/0%. `missing_evidence` và `unknown_attendance_consultations` báo số lịch này.
- Có join xác thực vẫn là bằng chứng dương ngay cả khi lịch cũ thiếu độ bao phủ theo dõi.
- Lịch bị hủy, khung giờ không hợp lệ, chưa đến hạn hoặc yêu cầu random chưa có expert không phạt chuyên cần.
- Vắng không cộng thêm vào `late_consultations`. Trễ là có lần vào được xác thực nhưng sau start+3 phút.
- Khi một phần lịch chưa rõ, tỷ lệ chỉ dùng phần xác định được và status `PARTIAL_DATA`; FE cần hiện kèm độ phủ, không trình bày như toàn bộ dữ liệu.

Trạng thái hiện tại của expert đọc từ hồ sơ/tài khoản DB. `INACTIVE` không tự mang nghĩa nghỉ việc. Không có module HR nên departures vẫn `NOT_TRACKED`.

### Hài lòng

1, 2, 3, 4, 5 sao đều được lưu. Nếu có 5 đánh giá lần lượt 1–5 sao: hài lòng = 2/5 = 40%, điểm trung bình = 3,00. Không có đánh giá → `NO_REVIEWS`, percent null.

### Khách quay lại

Cùng người, cùng expert, có buổi trước đã hoàn thành trước lúc bắt đầu buổi sau. Nhiều lần quay lại của một người trong kỳ không nhân số khách. Tổng đội ngũ distinct theo người; không cộng máy móc mẫu số của từng chuyên gia.

## 5. RANDOM: deadline và preferred_date

| Thời điểm tạo yêu cầu (VN) | Hạn nhận |
|---|---|
| 00:00 đến trước 08:00 | 08:30 cùng ngày |
| 08:00 đến trước 19:00 | Thời điểm tạo + 30 phút |
| 19:00 đến hết ngày | 08:30 hôm sau |

- 19:50 → 08:30 hôm sau; bỏ hoàn toàn kiểu cộng 10 phút hôm trước +20 phút hôm sau.
- Đúng 08:30:00 còn đúng hạn; sau đó quá hạn.
- Có thể nhận trước hạn, không bắt chờ đến 08:30.
- DIRECT được xếp lịch tự động nên không nằm trong KPI tiếp nhận RANDOM.
- Khi xem toàn đội, yêu cầu chưa ai nhận đã quá hạn ảnh hưởng KPI đội/ngành; không gán trách nhiệm cá nhân. Khi lọc expert chỉ tính yêu cầu người đó đã nhận.
- `preferred_date` không ảnh hưởng deadline: 02:00 ngày 15/10 đặt mong muốn ngày 18/10 vẫn cần được nhận chậm nhất 08:30 ngày 15/10.

Swagger **Consultation requests**, request mới:

```json
{"assignment_type":"RANDOM","specialty":"HEALTH","preferred_date":"2026-10-18","note":"..."}
```

- Ngày mong muốn phải trong horizon hiện có 30 ngày, không quá khứ.
- Optional để tương thích client cũ. FE mới nên yêu cầu chọn ngày; mặc định hôm nay trước 19:00, ngày mai từ 19:00.
- Không giữ slot khi tạo RANDOM. Expert chọn khung giờ trống trên đúng ngày mong muốn khi accept.
- Accept sang ngày khác trả 422. Nếu ngày mong muốn đã hết, user cần hủy/tạo lại; BE không âm thầm dời ngày.

Response User/Expert có thêm:

`preferred_date`, `acceptance_opened_at`, `acceptance_deadline_at`, `accepted_at`, `acceptance_policy_version`, `acceptance_status`, `server_time`, `acceptance_notice`.

`acceptance_status`: `NOT_APPLICABLE`, `UNKNOWN`, `WAITING`, `OVERDUE`, `ACCEPTED_ON_TIME`, `ACCEPTED_LATE`, `CANCELLED`.

Admin appointments cũng nhận ngày mong muốn, deadline, accepted_at, policy version, server_time. `attention_reason=ACCEPTANCE_OVERDUE` khi RANDOM đang chờ đã quá hạn. Khi được nhận/hủy, dòng này tự rời danh sách quá hạn đang mở; lịch sử nhận trễ vẫn giữ.

## 6. Thông báo và tác vụ nền

- DIRECT: báo đúng expert được chọn, kèm ngày/giờ.
- RANDOM: gửi expert còn hoạt động, có role EXPERT, đúng chuyên khoa; bỏ qua người đặt nếu chính họ cũng là expert.
- User nhận thông báo hạn xử lý. Nội dung dùng “**chậm nhất**”, hôm nay/ngày mai tính theo giờ VN của server và luôn có ngày tuyệt đối dd/MM/yyyy để thông báo lưu lâu không bị hiểu nhầm.
- Chưa thấy join sau start+3 phút: Expert nhận “Chưa ghi nhận bạn vào phòng tư vấn”. Đây là nhắc tạm thời, không kết tội trễ khi webhook chưa tới.
- Join được xác thực muộn: thông báo cùng booking được cập nhật thành đã vào trễ và thời điểm vào thực tế.
- Webhook đến sau có thời điểm vào đúng giờ sớm hơn: sửa thông báo thành xác nhận đúng giờ, sửa KPI theo lần vào đầu. Không tạo thêm loạt thông báo và không mở lại phòng đã kết thúc.
- RANDOM quá deadline: event `ADMIN_ACCEPTANCE_OVERDUE`, category `APPOINTMENT`, deep link `nutrimom://admin/appointments/<id>`, vào hộp Admin và mục cần xử lý.
- Nhận muộn trước lượt quét nền vẫn tạo event quá hạn ngay trong transaction accept. Outbox/dedup chung tránh gửi lại.
- Expert dùng inbox `audience=USER` hiện có; Admin dùng `audience=ADMIN`. Chưa cần tạo một audience EXPERT riêng.

Mốc kết thúc lịch phát revision KPI, sau khoảng chờ bằng chứng có revision chuyên cần. Dòng đã hoàn tất theo dõi được đánh dấu để không quét lại toàn bộ lịch sử.

Chu kỳ mặc định 15 giây; mỗi loại xử lý tối đa 100 booking/lượt, `FOR UPDATE SKIP LOCKED`; event/dedup tồn tại trong DB. Không gửi cảnh báo quá hạn lịch sử hàng loạt lúc nâng cấp. Thông báo đang chờ, vắng/trễ là các khái niệm khác nhau.

## 7. Theo dõi độ đầy đủ, triển khai và migration

Migration mới **V53** giữ nguyên checksum V52 và hàm v1. Thêm timestamp lịch UTC, ngày mong muốn, deadline/version, trạng thái theo dõi, khoảng gián đoạn và dấu event chống trùng.

- Lịch mới ghi canonical UTC từ giờ Việt Nam ngay khi đặt/nhận, Video và KPI dùng cùng timestamp.
- Lịch cũ giữ fallback chuyển đổi LocalTime hiện có; lịch còn chờ ở tương lai được chuẩn hóa timestamp từ slot/metadata lịch đã có theo lô để không bỏ sót nhắc giờ. Không bịa join/accepted_at từ updated_at hoặc completed_at.
- RANDOM đang chờ có acceptance_opened_at đáng tin cậy được chuyển deadline v2. Lịch đã nhận cũ không bị viết lại lịch sử deadline.
- Không sửa/rút ngắn các gói 180 ngày, dữ liệu thanh toán hay người dùng thật.
- Không deploy/push hoặc chạy migration vào DB online trong lượt phát triển này.

Cấu hình triển khai:

```dotenv
NUTRIMOM_CONSULTATION_MONITOR_ENABLED=true
# Chỉ bật sau khi đã kiểm webhook LiveKit participant_joined có chữ ký đến BE đúng:
NUTRIMOM_VIDEO_PRESENCE_TRACKING_ENABLED=true
```

Cờ presence mặc định false để một hệ thống chưa cấu hình webhook không chấm expert vắng sai. Khi false: join xác thực vẫn có giá trị, lịch không có join giữ “chưa rõ”; deadline và nhắc lịch vẫn hoạt động. Đây là điều kiện kích hoạt độ tin cậy dữ liệu, không phải thao tác FE.

Khi bật, tác vụ probe LiveKit có xác thực, ghi heartbeat DB và các khoảng gián đoạn; heartbeat cách nhau >45 giây hoặc probe lỗi làm khoảng đó chưa đáng tin. Các khoảng gián đoạn liền nhau được gộp, không thêm một dòng mỗi 15 giây. Lịch giao với khoảng gián đoạn không bị suy luận vắng. `tracking_started_at` lưu lần theo dõi khỏe đầu tiên, không lấy từ đồng hồ trình duyệt.

Cần giám sát webhook trong vận hành: một probe provider thành công không tự chứng minh mọi webhook đều được giao. Cờ theo dõi chỉ nên bật sau smoke test và tắt khi kênh webhook lỗi kéo dài. Bằng chứng đến muộn luôn được dùng để sửa số liệu.

## 8. Báo cáo và FE làm sau

Swagger **Admin operations**:

```http
GET /api/v1/admin/reports/EXPERT_PERFORMANCE?fromDate=...&toDate=...&specialty=HEALTH&expertUserId=...&page=1&pageSize=20
GET /api/v1/admin/reports/EXPERT_PERFORMANCE/export?fromDate=...&toDate=...&specialty=HEALTH&expertUserId=...
```

- Không truyền `source` cho báo cáo này.
- Preview dùng envelope báo cáo hiện có. `summary.snapshot` là snapshot tổng phạm vi lọc; `items[]` có `expert`, `summary`, `axes` với cả current/previous và delta.
- Trang 1..1.000.000, pageSize 1..100; CSV tối đa 10.000 chuyên gia, vượt trả 422.
- CSV UTF-8 BOM, phòng formula injection, có múi giờ/thời điểm chụp/phiên bản và tử/mẫu, trạng thái bằng chứng, tỷ lệ hai kỳ.
- Tổng khách quay lại trong summary lấy distinct cấp đội, không cộng số từ các dòng expert.
- Dashboard 6 trục v2 dùng 5 lượt đọc cố định; báo cáo có dữ liệu dùng 8 lượt cố định mỗi trang/xuất, không N+1 theo số expert.

FE ở lượt kế tiếp: gọi v2 + extended, đọc minDate/coverage, thêm 2 trục và nhãn số liệu, hiện absent/late/unknown; User chọn ngày và thấy deadline; Expert có chuông/deep link đúng console/note đỏ quá hạn; cập nhật mục cần xử lý và lựa chọn báo cáo. Dùng server_time để đếm ngược; không gửi API theo từng giây/từng dòng.
