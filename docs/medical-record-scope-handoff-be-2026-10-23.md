# Bàn giao: phạm vi hồ sơ y tế giữa Lịch và Quản lý hồ sơ — 23/10/2026

Gửi: người phụ trách module **quản lý hồ sơ y tế** (`vn.nutrimom.medicalrecord`).
Từ: người phụ trách module **lịch** (`vn.nutrimom.calendar`).

## Tóm tắt một dòng

Lịch vừa được siết lại chỉ vẽ hồ sơ của **thai kỳ đang `ACTIVE`**; đổi lại, màn hình quản lý hồ sơ
phải mở ra **toàn bộ lịch sử, xuyên thai kỳ**, để mẹ so sánh thông số lần mang thai này với lần
trước. Phần việc bên lịch đã xong; phần còn lại nằm ở module của bạn và ở FE.

## Lỗi đã phát hiện

Tài khoản thật `0858977889` (môi trường dev) có 2 hồ sơ y tế:

| Hồ sơ | `occurred_at` | `pregnancy_id` | Trạng thái thai kỳ |
|---|---|---|---|
| `1d961b8a` Siêu âm 12 tuần | 20/10/2026 | `4a4ffea1` | `COMPLETED` (đóng ngày 20/10) |
| `19a7eb7d` Khám thai tuần 21 | 23/10/2026 | `6bedb0d0` | `ACTIVE` |

Lịch hiện 2 chấm, màn hình quản lý hồ sơ chỉ hiện 1 — vì hai bên lọc theo hai tiêu chí khác nhau:

- Lịch đọc hồ sơ **chỉ theo `owner_user_id`**, không biết tới thai kỳ.
- `GET /api/v1/medical-records` nhận tham số `pregnancy_id`, và FE **luôn** truyền thai kỳ hiện tại.

Hệ quả cho người dùng: bấm vào chấm ngày 20/10 trên lịch thì mở được chi tiết, nhưng quay lại danh
sách thì không thấy bản ghi đó đâu.

## Quyết định phân vai

| Màn hình | Phạm vi dữ liệu | Lý do |
|---|---|---|
| **Lịch** (`/api/v1/calendar/*`) | Chỉ thai kỳ `ACTIVE` | Lịch để theo dõi thai kỳ đang diễn ra. Trộn mốc khám của lần mang thai trước vào ô tháng này là nhiễu, và dẫn tới chấm không bấm được như trên. |
| **Quản lý hồ sơ** (`/api/v1/medical-records`) | Toàn bộ, xuyên thai kỳ | Giá trị của màn hình này là tra cứu và **so sánh** lịch sử. Khoá nó vào thai kỳ hiện tại thì mẹ mang thai lần 2 mất sạch dữ liệu lần 1. |

## Phần đã làm bên lịch (không cần bạn đụng vào)

- `CalendarMedicalRecordQueryRepository.findWindow` → đổi tên thành **`findActivePregnancyWindow`**,
  câu HQL nối thêm `app.pregnancies` và lọc `pregnancy.status = ACTIVE`.
  Entity `MedicalRecordEntity` **không bị sửa**, không có migration nào, không đổi contract API.
- Người chưa có thai kỳ `ACTIVE` nào → nguồn `MEDICAL_RECORD` rỗng; hai nguồn còn lại vẫn vẽ.
- Điều kiện `pregnancy.owner_user_id = :ownerUserId` được giữ cho chắc, phòng hồ sơ bị gán nhầm
  thai kỳ của người khác.
- Test: `CalendarEventsIntegrationTest#medicalRecordsOfAClosedPregnancyStayOffTheCalendar`
  (đóng thai kỳ 1 → mở thai kỳ 2 → chỉ mốc của thai kỳ 2 lên lịch). Toàn bộ suite BE xanh.
- Doc đã cập nhật: [CALENDAR_API.md](CALENDAR_API.md).

Lịch chia sẻ cho người nhà không bị ảnh hưởng — nguồn `MEDICAL_RECORD` vốn đã bị loại hoàn toàn ở đó.

## Phần cần bạn làm

### 1. Không khoá danh sách vào thai kỳ hiện tại

`MedicalRecordService.list` hiện đã đúng: `pregnancy_id` là **tuỳ chọn**, bỏ trống thì trả hết hồ sơ
của chủ sở hữu, sắp theo `occurred_at` giảm dần. Nên về code BE có thể **không phải sửa gì**.
Việc cần làm là **chốt rằng đây là hợp đồng chính thức** và đừng ai "sửa cho chặt" sau này: FE sẽ
bỏ `pregnancy_id` khỏi lời gọi mặc định và dựa vào hành vi này.

Kiểm lại giúp một điểm: `limit` mặc định 20 và phân trang bằng cursor offset. Khi gộp nhiều thai kỳ,
số bản ghi tăng lên — trang đầu có thể không còn đủ để hiện "lần này" trước khi người dùng cuộn.
Đó là việc của FE, nhưng nếu bạn thấy nên nâng trần `limit` thì nói trước để FE khỏi tự chế.

### 2. Thiếu API liệt kê thai kỳ — đây là chỗ chặn

`PregnancyController` hiện chỉ có `POST /`, `POST /calculate`, `GET /current`, `GET /{id}`,
`PATCH /{id}`. **Không có cách nào lấy danh sách thai kỳ của một người.**

Mỗi hồ sơ trả về có `pregnancy_id`, nhưng FE không biết id đó là thai kỳ nào, ngày dự sinh bao
nhiêu, lần thứ mấy — nên không nhóm được, không đặt nhãn được, và cũng không dựng nổi bộ lọc "xem
thai kỳ nào". Đề nghị bổ sung:

```
GET /api/v1/pregnancies?status=          # bỏ trống = tất cả
→ data: [ PregnancyResponse ]            # sắp theo created_at giảm dần
```

Dùng lại `PregnancyResponse` có sẵn là đủ (`id`, `status`, `estimated_due_date`, `created_at`…).
Nếu phần này thuộc người giữ module `pregnancy` chứ không phải bạn, chuyển tiếp giúp — FE đang chờ
đúng endpoint này.

### 3. Gợi ý bộ lọc cho màn hình so sánh

Khi FE dựng được bộ chọn thai kỳ, `pregnancy_id` đơn lẻ sẽ bí nếu người dùng muốn xem **hai** thai kỳ
cạnh nhau. Nếu thấy hợp lý thì mở `pregnancy_id` thành multi-value (`?pregnancy_id=a&pregnancy_id=b`),
giữ nguyên nghĩa cũ khi chỉ truyền một giá trị. Không gấp — ghi lại để khỏi quên.

## Những thứ cố ý **không** làm

- **Không** thêm cờ kiểu `include_past_pregnancies`. Mặc định của danh sách đã là "tất cả"; thêm cờ
  chỉ tạo hai đường code làm cùng một việc.
- **Không** đổi `pregnancy_id` của hồ sơ cũ sang thai kỳ mới. Dữ liệu đang đúng; sai là ở chỗ đọc.
- **Không** xoá mốc 20/10 trong dev. Nó là ca kiểm thử sống cho đúng tình huống này.

## Cách kiểm lại sau khi xong

Với tài khoản `0858977889` trên dev:

```bash
curl -s "http://localhost:5173/api/v1/medical-records?limit=50" -H "Authorization: Bearer <token>"
```

- Không truyền `pregnancy_id` → phải ra **2** bản ghi (`19a7eb7d` và `1d961b8a`).
- `GET /api/v1/calendar/events?from=2026-10-20&to=2026-10-20&types=MEDICAL_RECORD` → phải ra **0**
  bản ghi (trước khi sửa là 1).

## Trạng thái phía quản lý hồ sơ — 24/10/2026

Nhánh `fix/medical-record-pregnancy-scope`. Không migration, không đổi entity.

| Mục | Kết quả |
|---|---|
| 1. Danh sách xuyên thai kỳ | Giữ nguyên hành vi (bỏ `pregnancy_id` = mọi thai kỳ, `occurred_at` giảm dần) và **khoá bằng test** `MedicalRecordPregnancyScopeIntegrationTest`. `limit` giữ trần 100, không nâng |
| 2. API liệt kê thai kỳ | **Đã có** `GET /api/v1/pregnancies?status=` → `data: [PregnancyResponse]`, mới trước (`created_at`). `status` sai → 400 `INVALID_REQUEST_PARAMETER` |
| 3. `pregnancy_id` nhiều giá trị | Chưa làm, để sau |

Thay đổi đi kèm:
- Thai kỳ **không** `ACTIVE`: `gestational_week`, `gestational_day`, `trimester`, `days_until_due` là null
  (bị bỏ khỏi JSON vì Jackson `non_null`). Các số này tính tới hôm nay nên vô nghĩa với thai kỳ đã đóng.
  Áp cho `GET /pregnancies`, `GET /pregnancies/{id}`, `PATCH` và `GET /dashboard/mom?pregnancy_id=`.
  `GET /pregnancies/current` không đổi.
- Tạo hồ sơ cho thai kỳ đã đóng: **409 `PREGNANCY_NOT_ACTIVE`** (trước là 404). Sửa/xoá hồ sơ cũ vẫn được.
- Sửa lỗi lọc `from`/`to` lệch một ngày: so ngày theo `app.calendar.default-timezone`
  (Asia/Ho_Chi_Minh) thay vì ngày UTC.
