# Calendar API (lịch và nhắc nhở)

Lịch kiểu Google Calendar cho mẹ bầu. Nó **trộn ba nguồn**, trong đó hai nguồn đầu chỉ được đọc:

| Nguồn (`source`) | Lấy từ | Điều kiện hiện lên lịch |
|---|---|---|
| `MEDICAL_RECORD` | `app.medical_records` | Hồ sơ của chính mình, chưa xoá mềm; mốc là `occurred_at` |
| `CONSULTATION` | `app.consultation_requests` + `app.consultation_slots` | **Chuyên gia đã xác nhận**, tức yêu cầu đã có khung giờ (`PENDING_CONSULTATION` hoặc `COMPLETED`) |
| `REMINDER` | `app.calendar_reminders` (V29) | Nhắc nhở người dùng tự tạo, trạng thái khác `CANCELLED` |

Yêu cầu tư vấn còn `PENDING_EXPERT` chưa có mốc giờ nào nên không hiện; yêu cầu đã huỷ được trả
`slot_id` về null lúc huỷ nên cũng tự rụng khỏi lịch.

Tất cả endpoint cần JWT (`Authorization: Bearer <token>`). JSON dùng snake_case, bọc trong
`ApiResponse` (`data`, `meta`); lỗi trả `error.code` theo [error-codes.md](error-codes.md).
Module này **không thêm error code mới**.

**Nhắc nhở lặp được theo ngày/tuần/tháng** (vitamin hằng ngày, thuốc cách ngày, theo thứ như báo
thức điện thoại) — xem mục [Lặp lại](#lặp-lại-repeat).

**Phạm vi v1: chỉ chủ sở hữu.** `FamilyScope.SHARED_CALENDAR` đã có trong enum nhưng chưa mở cho
thành viên gia đình.

## Endpoints

| Method | Path | Ghi chú |
|---|---|---|
| GET | `/api/v1/calendar/events?from=&to=&timezone=&types=` | Danh sách đã trộn, sort `starts_at` tăng dần |
| GET | `/api/v1/calendar/month?year=&month=&timezone=` | Lưới tháng, chỉ ngày có mốc |
| GET | `/api/v1/calendar/reminders?from=&to=&status=&timezone=` | Nhắc nhở của mình |
| POST | `/api/v1/calendar/reminders` | Tạo nhắc nhở tự do, 201 |
| GET | `/api/v1/calendar/reminders/{id}` | Chi tiết (của người khác → 404) |
| PATCH | `/api/v1/calendar/reminders/{id}` | Cần `version` |
| PUT | `/api/v1/calendar/reminders/{id}/occurrences` | Đánh dấu MỘT lần lặp: đã làm / bỏ qua |
| DELETE | `/api/v1/calendar/reminders/{id}` | Xoá mềm, 204 |
| POST | `/api/v1/medical-records/{id}/reminders` | Nút "thêm vào lịch nhắc nhở", 201 |

`GET /events` và `GET /month` **không phân trang**: cửa sổ đã bị chặn trần nên số mốc của một người
luôn hữu hạn, và màn hình lịch vốn phải vẽ trọn khoảng đang xem.

## Khoảng ngày và múi giờ

`from`/`to` là **ngày ISO, bao gồm cả hai đầu**, hiểu theo `timezone`. Cửa sổ thực tế là nửa mở
`[from 00:00, to+1 ngày 00:00)`, nên một mốc đúng 00:00 ngày kế tiếp thuộc về cửa sổ sau.

`timezone` không gửi thì lấy theo thứ tự: tham số → `app.user_preferences.timezone` → `Asia/Ho_Chi_Minh`.

- Khoảng rộng quá **366 ngày**, hoặc `to` nhỏ hơn `from` → 422 `VALIDATION_ERROR`.
- Chuỗi múi giờ không tồn tại → 422 `VALIDATION_ERROR` (không phải 500).
- `month` ngoài 1–12 → 422 `VALIDATION_ERROR`.

Lưu ý về buổi tư vấn: `consultation_slots` lưu `slot_date`/`start_time` dạng naive mang nghĩa **giờ
Việt Nam**, không phải giờ người xem. Backend quy đổi qua `Asia/Ho_Chi_Minh` rồi mới xếp vào ô ngày
theo `timezone` của request, nên một khung giờ 08:00 VN vẫn nằm đúng ngày hôm trước khi xem từ
`America/Los_Angeles`.

## GET /calendar/events

`types` lọc theo nguồn, bỏ trống = cả ba. Nhiều giá trị: `types=REMINDER&types=CONSULTATION`.
Giá trị lạ trả **400 `INVALID_REQUEST_PARAMETER`** (hành vi chung của binding enum, không phải 422).

```json
{
  "source": "CONSULTATION",
  "source_id": "9f1c…",
  "title": "Buổi tư vấn",
  "subtitle": "Chuyên gia BS Lan",
  "starts_at": "2026-10-02T01:00:00Z",
  "ends_at": "2026-10-02T01:30:00Z",
  "date": "2026-10-02",
  "status": "PENDING_CONSULTATION",
  "deep_link": "nutrimom://consultations/9f1c…"
}
```

- `subtitle` là một dòng đã render sẵn (tên chuyên gia / loại hồ sơ + cơ sở / cơ sở của nhắc nhở) để
  FE vẽ một ô lịch mà không phải phân nhánh theo `source`.
- `date` là ngày địa phương theo `timezone` của request — backend tính sẵn để FE không lặp lại phép
  quy đổi và lệch ngày ở mốc biên.
- `ends_at` chỉ có với `CONSULTATION`; hai nguồn còn lại là mốc điểm.
- `status` là trạng thái của nguồn (`ConsultationStatus` hoặc trạng thái nhắc nhở); hồ sơ y tế không
  có trạng thái nên trả null.

Deep link theo quy ước chung `nutrimom://<resource>/<id>`: `nutrimom://medical-records/{id}`,
`nutrimom://consultations/{id}`, `nutrimom://calendar/reminders/{id}`.

## GET /calendar/month

```json
[ { "date": "2026-10-02", "event_count": 2, "types": ["MEDICAL_RECORD", "REMINDER"] } ]
```

Chỉ trả ngày có ít nhất một mốc. `types` sắp theo thứ tự khai báo enum
(`MEDICAL_RECORD`, `CONSULTATION`, `REMINDER`) nên ổn định giữa hai lần gọi.

## Nhắc nhở

`type`: `FOLLOW_UP` (tái khám), `ROUTINE_CHECKUP` (khám định kỳ), `CUSTOM` (tự do).
`status`: `SCHEDULED` → `DONE` hoặc `CANCELLED`.

```json
POST /api/v1/calendar/reminders
{
  "type": "ROUTINE_CHECKUP",
  "title": "Khám định kỳ tháng 3",
  "starts_at": "2026-10-20T02:00:00Z",
  "timezone": "Asia/Ho_Chi_Minh",
  "facility_name": "BV Hùng Vương",
  "note": "Mang sổ khám",
  "remind_minutes_before": 1440,
  "pregnancy_id": null
}
```

- `starts_at` bắt buộc ở **tương lai**; quá khứ → 422. Với chuỗi lặp nó là **điểm neo** của chuỗi.
- `remind_minutes_before` tối đa **10080** (7 ngày); null = không gửi thông báo, chỉ là mốc trên lịch.
- `pregnancy_id` nếu gửi phải là thai kỳ của mình, nếu không → 404.
- Response có thêm `remind_at` (mốc nhắc của **lần kế tiếp chưa bắn**, server tự tính),
  `next_occurrence` (lần lặp sắp tới tính từ bây giờ) và `date`.

### Lặp lại (`repeat`)

Mô hình giống phần "Repeat" của báo thức điện thoại: chọn giờ, chọn thứ, chọn tần suất. Bỏ trống
`repeat` = mốc một lần.

```json
"repeat": {
  "rule": "WEEKLY",
  "interval": 1,
  "days_of_week": ["MONDAY", "WEDNESDAY", "FRIDAY"],
  "times_of_day": ["08:00", "20:00"],
  "until": "2027-04-20"
}
```

| Trường | Ý nghĩa |
|---|---|
| `rule` | `DAILY` (mỗi N ngày) · `WEEKLY` (mỗi N tuần, vào các thứ đã chọn) · `MONTHLY` (mỗi N tháng, cùng ngày trong tháng) |
| `interval` | 1–365; bỏ trống = 1. **`rule=DAILY, interval=2` chính là "uống thuốc cách ngày"** |
| `days_of_week` | chỉ dùng cho `WEEKLY`; tên đầy đủ `MONDAY`…`SUNDAY`. Bỏ trống = lấy thứ của `starts_at`. Gửi kèm `rule` khác → 422 |
| `times_of_day` | tối đa **6** mốc giờ trong ngày; thuốc sáng/tối là **một** nhắc nhở chứ không phải hai. Bỏ trống = lấy giờ của `starts_at` |
| `until` | ngày cuối còn lặp; bỏ trống = không giới hạn. Trước ngày bắt đầu → 422 |

Các lần lặp **không được lưu thành dòng** — chúng được tính ra mỗi lần vẽ lịch. Nhờ vậy đổi giờ một
cái là cả chuỗi đổi theo, và chuỗi không giới hạn vẫn chạy được. Mỗi item trên `/events` của một
chuỗi có `recurring: true`, và `starts_at` của item chính là mốc của **đúng lần lặp đó**.

`MONTHLY` neo vào ngày 31 thì tháng ngắn hơn lùi về ngày cuối tháng (28/29/30), không nhảy sang
tháng sau.

Một request có quá nhiều mốc sau khi khai triển (trần 2000) → 422 báo thu hẹp khoảng. Thà báo lỗi
còn hơn âm thầm cắt bớt: lịch thiếu mốc nguy hiểm hơn lịch báo lỗi.

**Hai thứ cố ý không làm, so với spec mục 10:**

1. Không có scope `THIS|THIS_AND_FUTURE|ALL`. Sửa/xoá luôn là **cả chuỗi**. Muốn "từ mai về sau đổi
   giờ" thì đặt `repeat.until` = hôm nay cho chuỗi cũ rồi POST một chuỗi mới — hai lời gọi API, người
   dùng không thấy khác biệt, còn `THIS_AND_FUTURE` thật thì đắt hơn toàn bộ phần còn lại cộng lại.
2. Không có kiểu "thứ 2-4-6 nhưng cách tuần lẻ/chẵn" phức tạp hơn `interval`, và không có BYSETPOS.

### PUT /calendar/reminders/{id}/occurrences — đánh dấu một lần lặp

```json
{ "occurrence_at": "2026-10-20T13:00:00Z", "status": "DONE" }
```

`status`: `DONE` (đã uống / đã làm) · `SKIPPED` (cố ý bỏ qua) · bỏ trống = **bỏ đánh dấu**, mốc về
lại chưa làm. Upsert, nên bấm hai lần không sinh dòng thứ hai.

`occurrence_at` phải trùng khít một mốc tính ra từ quy tắc lặp, nếu không → 422 — nếu không thì bảng
ngoại lệ sẽ đầy dòng mồ côi không khớp mốc nào trên lịch.

Chỉ **ngoại lệ** được lưu: ngày nào chưa đụng tới thì không có dòng nào. Một nhắc nhở hằng ngày kéo
dài cả thai kỳ vì thế chỉ tốn số dòng bằng số lần người dùng thật sự bấm.

### PATCH

Field null = không đổi; `version` bắt buộc. Lệch `version` → 409 `VERSION_CONFLICT`.

Vì null đã mang nghĩa "không đổi", muốn bỏ hẳn phải gửi cờ: `"clear_remind_minutes_before": true`
(bỏ nhắc) hoặc `"clear_repeat": true` (bỏ lặp, về mốc một lần). Dời `starts_at` thì `remind_at` được
tính lại theo mốc mới.

Chuyển `status` sang `DONE` thì response kèm `next_suggestion` — mốc kế tiếp **gợi ý**, không được
lưu; client muốn thì tự gọi POST để tạo. Khoảng cách là hằng số phẳng: `FOLLOW_UP` +7 ngày,
`ROUTINE_CHECKUP` +28 ngày, `CUSTOM` không gợi ý. Đây là đường dành cho **mốc một lần** (tái khám
thưa). Chuỗi lặp gửi `status=DONE` sẽ bị **422**: nó không có khái niệm "cả chuỗi đã xong" — đánh
dấu từng lần bằng `PUT …/occurrences`, muốn dừng hẳn thì `CANCELLED` hoặc đặt `repeat.until`.

### DELETE

Xoá mềm (`deleted_at`), trả 204. Lần xoá thứ hai → 404. Khác với `status=CANCELLED`: huỷ thì mốc
biến khỏi lịch nhưng vẫn tra được qua `GET /calendar/reminders?status=CANCELLED`.

## POST /medical-records/{id}/reminders

Nút "thêm vào lịch nhắc nhở" trên màn hình một hồ sơ y tế.

```json
{ "type": "FOLLOW_UP", "starts_at": "2026-10-20T01:30:00Z", "remind_minutes_before": 120 }
```

`title`, `facility_name` và `pregnancy_id` được **copy** từ hồ sơ (không tham chiếu ngược: hồ sơ có
thể bị xoá mềm mà mốc nhắc vẫn phải hiện đúng), `source_record_id` trỏ về hồ sơ gốc. Tiêu đề mặc
định là `"Tái khám: <tiêu đề hồ sơ>"` (hoặc `"Khám định kỳ: …"` với `ROUTINE_CHECKUP`); gửi `title`
để ghi đè. Hồ sơ của người khác → 404.

Route nằm dưới `/medical-records` để quyền truy cập dùng lại chính query owner-scoped của hồ sơ,
không mở thêm bề mặt IDOR dưới `/calendar`.

## Job nhắc lịch

Quét mỗi 5 phút (`NUTRIMOM_CALENDAR_JOB_INTERVAL`, ISO-8601 duration) các dòng
`status=SCHEDULED AND deleted_at IS NULL AND notified_at IS NULL AND remind_at <= now`, rồi gọi
`NotificationService.publish(..., REMINDER, ...)`.

- **Idempotent**: mỗi dòng được giành bằng một `UPDATE ... SET notified_at WHERE notified_at IS NULL`;
  chỉ bên nhận được rowcount 1 mới gửi. Chạy nhiều instance không bắn trùng. Claim cố ý **không bump
  `version`** để một thao tác của job không biến PATCH hợp lệ của client thành `VERSION_CONFLICT`.
- Mỗi dòng một transaction riêng: một dòng lỗi không kéo đổ các dòng còn lại, và lượt quét sau thử lại.
- Mốc đã trôi qua hơn **24 giờ** (`stale-after-hours`) thì chỉ claim, không gửi — tránh bắn "sắp tới
  giờ" cho một buổi hẹn của tuần trước khi hệ thống sống lại sau một lần ngừng chạy.
- Thân thông báo chỉ nhắc giờ và cơ sở y tế, **không kèm `note`**: ghi chú là nội dung sức khoẻ người
  dùng tự viết, mà thông báo có thể hiện trên màn hình khoá.
- Tắt bằng `NUTRIMOM_CALENDAR_JOB_ENABLED=false` — khi tắt thì không có scheduler nào được tạo.
  Profile test tắt sẵn.

Thông báo vẫn tôn trọng tuỳ chọn và quiet hours của người dùng, vì nó đi qua đúng
`NotificationService.publish` như mọi module khác — xem [NOTIFICATION_API.md](NOTIFICATION_API.md).

## Dashboard

Module này cắm hai block của `GET /api/v1/dashboard/mom` (spec mục 05):

- `next_appointment`: mốc gần nhất trong 90 ngày tới, chỉ xét `CONSULTATION` và `REMINDER` (hồ sơ y
  tế ghi việc đã xảy ra, không phải hẹn sắp tới). Shape giống một item của `/calendar/events`.
- `upcoming_reminders`: tối đa 5 nhắc nhở sắp tới, cùng shape.

Cả hai được bọc try/catch: lịch lỗi thì block về null/rỗng chứ không làm đổ endpoint dashboard.

## Lỗi

| Code | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_ERROR` | 422 | Thiếu `from`/`to`, `to < from`, khoảng > 366 ngày, `month` ngoài 1–12, múi giờ không tồn tại, `starts_at` ở quá khứ, `remind_minutes_before` > 10080, tiêu đề rỗng/quá dài, quy tắc lặp sai (chọn thứ cho kiểu không phải WEEKLY, `until` trước ngày bắt đầu, quá 6 mốc giờ/ngày), mốc đánh dấu không thuộc chuỗi, đánh dấu DONE cho cả chuỗi lặp, khoảng xem khai triển ra quá 2000 mốc |
| `VERSION_CONFLICT` | 409 | PATCH với `version` cũ |
| `RESOURCE_NOT_FOUND` | 404 | Nhắc nhở/hồ sơ y tế/thai kỳ không tồn tại **hoặc không phải của mình** |
| `INVALID_REQUEST_PARAMETER` | 400 | `types`/`status` gửi giá trị không có trong enum |
| `UNAUTHORIZED` | 401 | Thiếu hoặc sai token |
