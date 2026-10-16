# KPI chuyên gia v3 — chuyên cần theo lịch, đúng giờ theo thao tác vào phòng

Nhánh: `feat/admin-operations-dashboard`. Múi giờ nghiệp vụ: `Asia/Ho_Chi_Minh`.

## 1. API và chuyển phiên bản

Dashboard dùng:

```http
GET /api/v1/admin/experts/performance?calculationVersion=3&includeExtendedMetrics=true
Authorization: Bearer <token admin>
```

Swagger: **Admin expert performance**. Các bộ lọc `fromDate`, `toDate`, `specialty`, `expertUserId` giữ nguyên tên.
`specialty` gồm `HEALTH`, `OBSTETRICS`, `PSYCHOLOGY`. Nếu chuyên gia không thuộc chuyên khoa đã chọn thì trả 404; không tự bỏ bộ lọc.

Hai API báo cáo dùng cùng bộ lọc và cùng công thức v3:

```http
GET /api/v1/admin/reports/EXPERT_PERFORMANCE?calculationVersion=3
GET /api/v1/admin/reports/EXPERT_PERFORMANCE/export?calculationVersion=3
```

V1/v2 vẫn giữ định nghĩa cũ để FE đang chạy không nhận cấu trúc/công thức mới bất ngờ.
FE phải chuyển đồng thời dashboard, bộ giải mã response, giải thích công thức và báo cáo sang `calculationVersion=3`.
Dashboard không truyền phiên bản vẫn mặc định v1; báo cáo mặc định v2. Không dùng v2 rồi kỳ vọng số liệu v3.

## 2. Kỳ KPI và số ngày thật

Không truyền ngày: từ ngày 1 đến **ngày cuối tháng hiện tại**, có cả ngày nghỉ dự kiến trong phần còn lại của tháng.

| Tháng | Số ngày | Khung chuẩn của một expert có mặt từ đầu tháng |
| --- | ---: | ---: |
| 10/2026 | 31 | 744 |
| 04/2026 | 30 | 720 |
| 02/2026 | 28 | 672 |
| 02/2028 | 29 | 696 |

Mỗi ngày có 24 khung, mỗi khung 30 phút trong 08:00–20:00, gồm cuối tuần vì lịch hiện tại mặc định mở hằng ngày.
Không cố định tháng là 30 ngày. Chọn đúng một tháng trọn vẹn thì kỳ trước là tháng lịch trước, dù số ngày khác nhau.
Ví dụ 01–31/10 so với 01–30/09; 01–28/02 so với 01–31/01.

Với khoảng tùy chọn không trọn tháng, kỳ trước là khoảng liền trước có cùng số ngày.
Ví dụ 01–15/10 so với 16–30/09. Đây là phép chọn khoảng ngày, không phải ngày expert nhận lịch đầu tiên.

Hai ngày phải cùng có mặt, đúng thứ tự, tối đa 366 ngày; ngày đầu không sau hôm nay, ngày cuối không vượt cuối tháng hiện tại.
Ngày làm việc trước khi expert được tạo trong DB không được đưa vào mẫu số của người đó.

### Mốc dữ liệu và kỳ đầu chưa đầy đủ (cập nhật 16/10/2026)

`data_coverage.filter_min_date` là ngày tạo hồ sơ expert sớm nhất theo giờ Việt Nam trong **DB mà BE đang kết nối**, không phụ thuộc chuyên khoa/expert đang lọc. Không dùng ngày seed của DB local hay một ngày ghi cứng.
Đây là mốc lịch làm việc có thể tính được, không phải ngày tạo máy chủ PostgreSQL. Lần kiểm tra chỉ đọc DB đang cấu hình ngày 16/10 ghi nhận mốc này là **22/09/2026**; lịch tư vấn đầu tiên là **28/09/2026**. Hai mốc khác nhau vì v3 tính chuyên cần từ lịch làm việc, không đợi có booking.

- `fromDate` tường minh trước `filter_min_date` bị từ chối 422, dùng chung cho dashboard/preview/CSV.
- Không truyền ngày vẫn nhận cả tháng mặc định; nếu tháng đó chứa ngày bắt đầu có dữ liệu, các ngày trước khi expert tồn tại không được thêm vào mẫu số.
- `earliest_expert_date` là mốc của phạm vi đang chọn. `current_effective_from_date` và `previous_effective_from_date` là ngày đầu có dữ liệu trong mỗi kỳ; null khi chưa có.
- `current_period_complete`/`previous_period_complete` nghĩa là phạm vi đã tồn tại từ đầu kỳ; **không** có nghĩa tất cả ngày trong tháng đã trôi qua hoặc tất cả buổi đã có bằng chứng check-in.
- `comparison_status`: `COMPLETE`, `PARTIAL_CURRENT_PERIOD`, `PARTIAL_PREVIOUS_PERIOD`, `NO_CURRENT_PERIOD_DATA`, `NO_PREVIOUS_PERIOD_DATA`.
- Chỉ trả chênh lệch khi **cả hai kỳ có đủ thời gian dữ liệu** và cả hai giá trị trục đều `AVAILABLE`; còn lại trả null, vẫn giữ tỷ lệ/tử số/mẫu số thật.
- Mỗi dòng báo cáo có `data_coverage` riêng theo ngày tạo hồ sơ expert; không dùng mốc tổng của đội ngũ để kết luận kỳ trước của expert mới đã đầy đủ. CSV kèm các cột ngày hiệu lực/đủ kỳ/trạng thái so sánh.

Ví dụ expert bắt đầu 22/09: tháng 9 chỉ có 9 × 24 = 216 khung chuẩn. `213/216 = 98,61%`; tháng 10 `720/744 = 96,77%`. Khối lượng tăng, tỷ lệ chuyên cần không tăng. Vì kỳ tháng 9 chưa đầy đủ nên API không trả xu hướng tăng/giảm cả tháng.

Với hai kỳ đều đủ thời gian dữ liệu: hài lòng `3/3 = 100%`, `10/12 = 83,33%`, chênh lệch **−16,67 điểm phần trăm** là đúng. Số đánh giá tốt tăng không đồng nghĩa tỷ lệ tăng; đánh giá 1–3 sao vẫn ở mẫu số.

FE dùng `filter_min_date` cho cả hai lịch chọn ngày ở Dashboard và Báo cáo, giữ mốc khi đổi query. Preset 30 ngày được rút ngắn tới mốc khi hệ thống chưa có đủ 30 ngày dữ liệu. Không gọi thêm endpoint metadata hoặc gọi từng trục.

Chuyên cần là **lịch làm việc dự kiến/đang lưu** của cả kỳ. Các KPI cuộc tư vấn chỉ tính sự kiện đã đủ điều kiện tại `snapshot_at`:
lịch tương lai không bị coi là chưa hoàn thành hay trễ giờ.

## 3. Chuyên cần

Nguồn thật: `expert_profiles.created_at`, `expert_day_offs`, `consultation_slots` của chính DB mà BE được cấu hình kết nối.
Không dùng seed cục bộ để thay số liệu DB online. Không dùng trạng thái hoàn thành hay LiveKit để suy ra chuyên cần.

Cho từng expert trong kỳ:

```text
ngày bắt đầu tính = max(ngày đầu kỳ, ngày tạo hồ sơ expert theo giờ Việt Nam)
khung chuẩn = số ngày từ ngày bắt đầu tính đến ngày cuối kỳ × 24
khung nghỉ = ngày nghỉ × 24
             + khung CLOSED nằm ngoài ngày nghỉ
             − khung BOOKED nằm trong ngày nghỉ
khung làm việc = khung chuẩn − khung nghỉ
chuyên cần (%) = khung làm việc / khung chuẩn × 100
```

Quy tắc dựa trên lịch đặt expert hiện có:

- Không có dòng slot nghĩa là giờ mở, không phải thiếu dữ liệu.
- `expert_day_offs` đánh dấu nghỉ cả ngày.
- `CLOSED` là nghỉ một khung 30 phút, đúng lưới 08:00–20:00.
- Một giờ đóng trong ngày đã nghỉ không bị trừ lần nữa.
- `BOOKED` được ưu tiên giữ là giờ làm việc kể cả ngày có đánh dấu nghỉ, vì thao tác nghỉ hiện tại vẫn giữ các lịch đã đặt.
- Expert `INACTIVE` không tự động được xem là nghỉ toàn tháng hay đã nghỉ việc. Muốn phản ánh nghỉ phải có lịch nghỉ.

Ví dụ một expert có mặt đủ tháng 10, nghỉ ngày 31, đóng thêm hai khung ngày 30 và vẫn có một khung đã đặt ngày 31:

```text
khung chuẩn = 31 × 24 = 744
khung nghỉ = 24 + 2 − 1 = 25
khung làm việc = 719
chuyên cần = 719 / 744 × 100 = 96,64%
```

Đối với một khoa hoặc toàn bộ khoa:

```text
chuyên cần nhóm = tổng khung làm việc của các expert / tổng khung chuẩn của các expert × 100
```

Không cộng các tỷ lệ phần trăm; không lấy trung bình các khoa có quy mô khác nhau.
Ví dụ hai expert cùng có 744 khung chuẩn, một người nghỉ 24 khung: nhóm là `(744 + 720) / 1488 = 98,39%`.
Expert mới vào ngày 15/10 chỉ đóng góp 17 ngày × 24 = 408 khung trong tháng đó.

Nếu không có expert/khung chuẩn phù hợp: `percent=null`, trạng thái `NO_WORK_SCHEDULE`; FE hiển thị `—`.

Response `schedule_summary`:

| Field | Ý nghĩa |
| --- | --- |
| `unit` | Luôn `HALF_HOUR_SLOTS` |
| `standard_slots` | Tổng khung chuẩn |
| `available_slots` | Tổng khung làm việc |
| `off_slots` | Tổng khung nghỉ sau khi loại trùng và ưu tiên BOOKED |
| `day_off_days` | Số bản ghi nghỉ ngày, cộng trên các expert; ngày có BOOKED vẫn có thể có đánh dấu nghỉ |
| `closed_slots` | Khung CLOSED ngoài những ngày đã đánh dấu nghỉ |

Lưu ý dữ liệu: đây là lịch **đang lưu**, không phải nhật ký chấm công vật lý. Lịch nghỉ cũ đã bị xóa/đổi trước đây không thể tái tạo.
Chuyên khoa được nhóm theo hồ sơ hiện tại; DB chưa có lịch sử chuyển khoa hoặc ngày kết thúc hợp đồng.

## 4. Đúng giờ: ghi nhận lần bấm đầu bằng giờ máy chủ

```http
POST /api/v1/consultation-requests/{requestId}/video/check-in
Authorization: Bearer <token expert được phân công>
```

Không có body, không gửi giờ trình duyệt. Server ghi giờ nhận thao tác, kiểm tra tài khoản active, role EXPERT,
hồ sơ active, đúng expert được phân công, user còn active và lịch đã được xác nhận.
Khóa dòng lịch khi ghi để hai thao tác đồng thời không sinh hai lần check-in.

Ví dụ buổi 08:00–08:30:

| Thời điểm server nhận thao tác | Kết quả |
| --- | --- |
| 07:54:59 | Chưa mở: 409 `VIDEO_NOT_OPEN`; không ghi check-in |
| 07:55:00 | Cho phép, đúng giờ |
| 08:00:00 | Đúng giờ |
| 08:03:00 | Đúng giờ, ranh giới bao gồm |
| 08:03:01 | Trễ giờ |
| 08:15:00 | Trễ giờ, dù sau đó hoàn thành thành công |
| 08:35:00 trở đi, chưa có receipt | Hết cửa sổ vào phòng: 409 `VIDEO_ENDED` |

Cửa sổ ghi mới được giữ tương thích với cửa sổ video hiện tại: từ bắt đầu −5 phút đến trước kết thúc +5 phút.
Lịch hủy/hoàn thành không được ghi lần đầu. Một receipt đã ghi hợp lệ được trả lại khi gọi lặp,
kể cả đã hoàn thành, miễn tài khoản và quyền phân công vẫn hợp lệ. Gọi lặp không sửa timestamp và không phát thông báo lặp.

Response ví dụ:

```json
{
  "request_id": "<uuid>",
  "checked_in_at": "2026-10-15T01:03:00Z",
  "scheduled_starts_at": "2026-10-15T01:00:00Z",
  "on_time_until": "2026-10-15T01:03:00Z",
  "on_time": true,
  "evidence": "SERVER_ENTRY_ACTION"
}
```

Thời gian có thể được serialize ở UTC; đổi sang giờ Việt Nam để hiển thị. Đây là object trong `data` của envelope API thông thường.

Luồng tương thích đã nối sẵn: `POST .../video/join` của expert cũng ghi receipt trước khi gọi nhà cung cấp video.
Transaction receipt kết thúc trước transaction kết nối video; provider lỗi không xóa receipt.
User vào phòng không tạo receipt của expert. `GET .../video` hoặc mở trang xem trước không ghi điểm danh.

Khi FE nối riêng thao tác trước khi điều hướng: gọi check-in một lần tại nút vào phòng đã mở khóa;
chặn bấm kép, không gọi trong render/effect/polling. Có thể dùng thẳng luồng `/video/join` hiện tại nếu đó là nút vào phòng thực tế.
Không cần gửi thêm hai request cho cùng một thao tác nếu không có nhu cầu điều hướng trước bước lấy token.

## 5. Mẫu số Đúng giờ và dữ liệu lịch sử

Một buổi vào mẫu số xét đúng giờ khi `scheduled_start + 3 phút <= snapshot_at`.
Buổi đang tư vấn vẫn có thể được đánh giá đúng giờ; không phải chờ hoàn thành.
Buổi hủy và yêu cầu chưa được expert nhận không nằm trong tập này.

```text
known = có receipt hợp lệ
        HOẶC cửa sổ vào phòng bắt đầu sau/khi cơ chế check-in được triển khai
đúng giờ = receipt đầu tiên <= scheduled_start + 3 phút
tỷ lệ đúng giờ = số buổi đúng giờ / số buổi đã biết kết quả × 100
```

`checkin_tracking_started_at` được tạo bằng thời gian DB khi chạy migration V55.
Buổi lịch sử trước mốc đó mà không có receipt là **unknown**, không phải 0%, vắng, trễ hay mặc định đúng giờ.
Một buổi mới đã quá hạn mà chưa bấm vào là `not_checked_in`; khi bấm trễ sẽ chuyển thành `late`.

Response `checkin_summary`:

- `eligible_consultations`: tất cả buổi đã hết 3 phút cho phép, gồm cả unknown.
- `checked_in_consultations`: có receipt hợp lệ.
- `on_time_consultations`: receipt đúng giờ.
- `late_consultations`: receipt sau hạn.
- `not_checked_in_consultations`: đã được tracking nhưng chưa có receipt khi hết hạn.
- `unknown_consultations`: buổi lịch sử không đủ bằng chứng.

Trục PUNCTUALITY: mẫu số = `eligible_consultations − unknown_consultations`;
`missing_evidence` = unknown. Khi chỉ có unknown: `NO_CHECKIN_EVIDENCE`, `percent=null`.
Có cả known và unknown: `PARTIAL_DATA`, không so chênh lệch với kỳ trước để tránh so mẫu thiếu.

Thông báo quá 3 phút và xác nhận đến trễ dùng receipt server. Webhook LiveKit vẫn phục vụ video và dữ liệu v2,
nhưng không ghi đè receipt hay kết quả đúng giờ v3. Scheduler làm mới KPI khi hết thời gian cho phép,
kể cả expert đã check-in từ sớm; không chờ đến cuối buổi.

## 6. Những trục khác và so sánh kỳ trước

Hài lòng giữ công thức: số đánh giá 4–5 sao / tất cả đánh giá 1–5 sao của các buổi đã hoàn thành.
3 sao vẫn ở mẫu số và làm giảm tỷ lệ, không bị bỏ qua. Buổi chưa có đánh giá được báo riêng là thiếu đánh giá.
Hoàn thành: số buổi hoàn thành / tổng buổi đến hạn kết thúc.
Tiếp nhận đúng hạn và Khách quay lại giữ công thức v2, có khi `includeExtendedMetrics=true`.

Mỗi axis trả `current`, `previous`, `change_percentage_points`. Các giá trị đều tính từ bản ghi DB trong hai khoảng ngày được trả về.

```text
thay đổi điểm phần trăm = tỷ lệ kỳ hiện tại − tỷ lệ kỳ trước
ví dụ: 96,77% − 100% = −3,23 điểm phần trăm
```

Đây không phải tăng trưởng tương đối `(hiện tại − trước) / trước`.
Không có mẫu số hoặc bằng chứng check-in/tiếp nhận không đầy đủ thì chênh lệch trả null, FE hiển thị `—`.
Không biến null thành 0 để vẽ đầy radar. Các tỷ lệ được làm tròn 2 chữ số bằng HALF_UP.

V3 trả `summary` về số buổi/đánh giá và hai nhóm mới `schedule_summary`, `checkin_summary`.
Không dùng các field `absent_consultations` hoặc `unknown_attendance_consultations` cũ để hiển thị chuyên cần theo lịch;
hai field cũ không áp dụng cho v3. Dùng ngày/khung nghỉ từ `schedule_summary`, trễ từ `checkin_summary`.

## 7. Triển khai và giới hạn

BE cần restart/build bản mới để Flyway chạy V55 và V56 theo thứ tự. Không xóa/sửa migration đã triển khai.
Không cần bật webhook/presence tracking LiveKit để hai KPI v3 này hoạt động.
Không backfill timestamp bấm nút cho lịch sử, không xóa bằng chứng kết nối video hiện có.

Các truy vấn dashboard là tổng hợp theo bộ lọc; báo cáo lấy theo trang rồi tổng hợp theo danh sách ID trong một lượt,
không gọi từng expert. Dashboard sáu truy vấn aggregate/revision; báo cáo mười truy vấn không tăng theo số dòng trang.
Export tối đa 10.000 expert, CSV có chống công thức spreadsheet. Dashboard không xuất dữ liệu lâm sàng hay token phòng.
Thay đổi lịch nghỉ, slot và receipt tạo revision làm mới Admin sau commit. Scheduler xử lý tối đa 100 lịch mỗi lượt,
mặc định quét 15 giây; nếu có backlog thì tiếp tục các lượt sau. Các thao tác vào phòng dùng quota thông thường 100 request/phút/tài khoản.

Lượt triển khai này chỉ thay BE; FE cần nối contract v3 trong lượt sau.
