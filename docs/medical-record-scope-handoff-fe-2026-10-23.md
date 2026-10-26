# Bàn giao FE: hồ sơ y tế trên Lịch vs. màn hình Quản lý hồ sơ — 23/10/2026

Gửi: người phụ trách **FE**.
Từ: người phụ trách module **lịch** (BE).

## Tóm tắt một dòng

BE vừa siết lịch lại chỉ trả hồ sơ y tế của **thai kỳ đang `ACTIVE`**. Đổi lại, màn hình
**Quản lý hồ sơ** phải mở ra **toàn bộ lịch sử, xuyên thai kỳ**, để mẹ so sánh lần mang thai này với
lần trước. Contract API không đổi, nhưng FE phải bỏ cách gọi hiện tại.

## Lỗi đang gặp

Tài khoản `0858977889` trên dev: lịch hiện **2 chấm xanh** (20/10 và 23/10), nhưng
`/app/profile/records` chỉ hiện **1 bản ghi** (23/10).

| Hồ sơ | Ngày | Thai kỳ | Trạng thái thai kỳ |
|---|---|---|---|
| Siêu âm 12 tuần | 20/10 | `4a4ffea1` | `COMPLETED` |
| Khám thai tuần 21 | 23/10 | `6bedb0d0` | `ACTIVE` |

Nguyên nhân: lịch đọc hồ sơ theo người dùng (vét cả thai kỳ cũ), còn
`src/features/maternity/model/records-page-data.ts:33` (repo FE)
luôn gắn `pregnancy_id` của thai kỳ hiện tại. Hai bên lọc hai kiểu nên lệch nhau.

## Quyết định phân vai

- **Lịch** = theo dõi thai kỳ đang diễn ra → chỉ hồ sơ của thai kỳ `ACTIVE`.
- **Quản lý hồ sơ** = tra cứu và **so sánh** → toàn bộ hồ sơ, mọi thai kỳ.

## Phía BE lịch: đã xong, FE không phải đổi gì

`GET /api/v1/calendar/events` và `/month` giữ nguyên request lẫn response. Chỉ là **dữ liệu ít đi**:
mốc khám của thai kỳ đã đóng (`COMPLETED` / `LOSS_REPORTED` / `ARCHIVED`) không còn lên lịch nữa.

Với tài khoản trên, `?from=2026-10-20&to=2026-10-20&types=MEDICAL_RECORD` nay trả mảng rỗng — chấm
ngày 20/10 biến mất, đúng như mong đợi. Người chưa có thai kỳ `ACTIVE` nào thì lịch không có nguồn
hồ sơ, nhưng nhắc nhở và buổi tư vấn vẫn vẽ bình thường.

Nếu đang có test/snapshot FE nào chốt rằng hồ sơ cũ phải lên lịch thì sửa lại theo hướng này.

## Phía FE: việc cần làm ở màn hình Quản lý hồ sơ

### 1. Bỏ `pregnancy_id` khỏi lời gọi danh sách

`GET /api/v1/medical-records` coi `pregnancy_id` là **tuỳ chọn**: bỏ trống thì BE trả hết hồ sơ của
người dùng, sắp theo `occurred_at` giảm dần, phân trang cursor như cũ. Đã kiểm trên dev — trả đủ 2
bản ghi của cả hai thai kỳ.

Cần sửa:
- `buildMedicalRecordsQuery` — `pregnancy_id` thành tham số không bắt buộc (dùng cho bộ lọc, không
  phải mặc định).
- `RecordsPage.loadRecords` — đang nhận `activePregnancyId` làm tham số bắt buộc và chỉ chạy sau khi
  `loadPregnancy` xong.

### 2. Đừng chặn màn hình khi không có thai kỳ `ACTIVE`

Hiện trang chờ `pregnancyApi.current` rồi mới tải danh sách. Mẹ vừa sinh xong (thai kỳ `COMPLETED`,
chưa mở thai kỳ mới) sẽ rơi vào trạng thái rỗng dù vẫn còn nguyên hồ sơ cũ — đúng người cần xem lại
lịch sử nhất. Danh sách phải tải được độc lập với thai kỳ hiện tại; thai kỳ `ACTIVE` chỉ còn cần cho
nút **Thêm hồ sơ** (BE bắt buộc `pregnancy_id` là thai kỳ `ACTIVE` khi tạo) — không có thì vô hiệu
hoá riêng nút đó kèm lời giải thích.

### 3. Nhóm và đánh dấu theo thai kỳ

Mỗi bản ghi trả về đều có `pregnancy_id`. Gộp cả hai thai kỳ vào một danh sách phẳng sẽ khó đọc —
mẹ không phân biệt được mốc nào của lần nào. Đề xuất: nhóm theo thai kỳ, nhóm thai kỳ hiện tại mở
sẵn ở trên, các thai kỳ cũ gập lại kèm nhãn (ví dụ *"Thai kỳ trước · dự sinh 14/02/2027"*).

**Cập nhật 24/10:** BE đã có `GET /api/v1/pregnancies?status=` (mới trước). Thai kỳ không `ACTIVE`
không còn trả `gestational_week`/`gestational_day`/`trimester`/`days_until_due`. Tạo hồ sơ cho thai kỳ
đã đóng trả 409 `PREGNANCY_NOT_ACTIVE`. Chi tiết ở cuối [doc BE](medical-record-scope-handoff-be-2026-10-23.md).

**Phụ thuộc (đã gỡ ngày 24/10):** trước đây **chưa có API liệt kê thai kỳ** — `PregnancyController` chỉ có
`GET /current` và `GET /{id}`, nên FE không tra được tên/ngày dự sinh của một `pregnancy_id` cũ.
Đã đề nghị BE bổ sung `GET /api/v1/pregnancies` (xem
[doc gửi team quản lý hồ sơ](medical-record-scope-handoff-be-2026-10-23.md)). Trong lúc chờ, có thể
gọi `GET /api/v1/pregnancies/{id}` cho từng `pregnancy_id` lạ xuất hiện trong danh sách — chạy được
ngay, nhưng đừng xem là giải pháp lâu dài.

### 4. Phân trang

`limit` đang là `12`. Gộp nhiều thai kỳ thì số bản ghi tăng, trang đầu dễ bị hồ sơ cũ chiếm chỗ nếu
người dùng lọc theo khoảng ngày. Giữ nguyên cursor hiện có là được, chỉ lưu ý khi test "Tải thêm".

### 5. Deep link từ lịch

`nutrimom://medical-records/{id}` và route chi tiết vẫn hoạt động cho mọi hồ sơ (API chi tiết lọc
theo chủ sở hữu, không theo thai kỳ). Sau khi làm xong mục 1, bấm từ lịch về danh sách sẽ không còn
cảnh "mở được chi tiết nhưng danh sách không có".

## Cách nghiệm thu

Tài khoản `0858977889` trên dev, sau khi FE sửa:

| Màn hình | Kỳ vọng |
|---|---|
| Lịch tháng 10/2026 | Ngày 20/10 **không còn** chấm hồ sơ y tế; ngày 23/10 vẫn còn |
| `/app/profile/records` | Hiện **cả 2** hồ sơ, nhóm theo thai kỳ |
| Bấm hồ sơ 20/10 | Mở chi tiết "Siêu âm 12 tuần" bình thường |
| Tài khoản không có thai kỳ `ACTIVE` | Danh sách vẫn hiện hồ sơ cũ; nút Thêm hồ sơ bị vô hiệu hoá |
