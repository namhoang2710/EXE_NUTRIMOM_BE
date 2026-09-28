# Consultation API (đặt lịch tư vấn 1-1)

User chọn chuyên gia và khung giờ để đặt buổi tư vấn 1-1, hoặc chỉ chọn chuyên khoa rồi để một
chuyên gia cùng khoa tiếp nhận. Chuyên gia quản lý lịch làm việc của mình và hoàn tất buổi tư vấn.

Tất cả endpoint cần JWT (`Authorization: Bearer <token>`). JSON dùng snake_case, bọc trong
`ApiResponse` (`data`, `meta`); lỗi trả `error.code` theo [error-codes.md](error-codes.md).
Trường `null` bị lược khỏi response (`default-property-inclusion: non_null`).

## Lưới khung giờ

Mọi chuyên gia **mặc định mở 08:00–20:00, mỗi khung 30 phút → 24 khung mỗi ngày**
(08:00, 08:30, … 19:30). Chuyên gia không phải mở lịch; chỉ cần **đóng** những khung bận.

Bảng `consultation_slots` vì thế không phải "danh sách lịch trống" mà là **danh sách ô đã bị
chiếm**: một dòng chỉ tồn tại khi ô đó `BOOKED` (user đặt) hoặc `CLOSED` (chuyên gia đóng).
Không có dòng nghĩa là ô còn trống.

- Đặt lịch được trong vòng **30 ngày** kể từ hôm nay (giờ Việt Nam). Ngoài khoảng này → 422.
- `start_time` phải đúng một mốc trong lưới. `08:15` hay `20:00` → 422.
- API **luôn trả đủ 24 ô**; ô không đặt được đi kèm lý do, không bị ẩn đi.

| `reason` | Nghĩa | Gợi ý nhãn trên UI |
|---|---|---|
| `PAST` | Khung giờ đã trôi qua (chỉ gặp ở ngày hôm nay) | Đã qua |
| `DAY_OFF` | Chuyên gia nghỉ cả ngày hôm đó | Nghỉ cả ngày |
| `BOOKED` | Đã có người khác đặt | Đã kín |
| `CLOSED` | Chuyên gia đóng riêng khung giờ này | Đã đóng |

Thứ tự ưu tiên khi nhiều lý do cùng đúng: `PAST` → `DAY_OFF` → `BOOKED` → `CLOSED`.

## Trạng thái yêu cầu tư vấn

```
RANDOM:  PENDING_EXPERT ──(chuyên gia accept)──▶ PENDING_CONSULTATION ──(complete)──▶ COMPLETED
DIRECT:                                          PENDING_CONSULTATION ──(complete)──▶ COMPLETED
                                     └──────────(user cancel)──────────▶ CANCELLED
```

Chuyên khoa (`specialty`): `PSYCHOLOGY`, `OBSTETRICS`, `HEALTH`.
Hình thức (`assignment_type`): `DIRECT` (tự chọn chuyên gia) hoặc `RANDOM` (thả vào pool).

## Endpoints

| Method | Path | Quyền | Ghi chú |
|---|---|---|---|
| GET | `/api/v1/experts?specialty=` | User | Chuyên gia đang hoạt động |
| GET | `/api/v1/experts/{userId}` | User | Chi tiết một chuyên gia |
| GET | `/api/v1/experts/{userId}/availability?date=` | User | Lịch một ngày, đủ 24 ô |
| POST | `/api/v1/consultation-requests` | User | Tạo yêu cầu, 201 |
| GET | `/api/v1/consultation-requests?page=1&pageSize=20` | User | Yêu cầu của mình |
| GET | `/api/v1/consultation-requests/{id}` | User | Chi tiết (của người khác → 404) |
| POST | `/api/v1/consultation-requests/{id}/cancel` | User | Khi chưa COMPLETED/CANCELLED |
| POST | `/api/v1/consultation-requests/{id}/review` | User | Chỉ khi `COMPLETED`, một lần |
| GET | `/api/v1/expert/me` | EXPERT | Hồ sơ của chính mình |
| GET | `/api/v1/expert/schedule?date=` | EXPERT | Lịch làm việc một ngày |
| PUT | `/api/v1/expert/schedule/slot` | EXPERT | Đóng/mở một khung giờ |
| PUT | `/api/v1/expert/schedule/day-off` | EXPERT | Bật/tắt nghỉ cả ngày |
| GET | `/api/v1/expert/schedule/summary?from=&to=` | EXPERT | Tổng hợp theo ngày, tối đa 31 ngày |
| GET | `/api/v1/expert/consultation-requests?type=assigned\|pool&status=&from=&to=&q=` | EXPERT | Buổi được giao / pool chờ nhận |
| POST | `/api/v1/expert/consultation-requests/{id}/accept` | EXPERT | Nhận yêu cầu RANDOM + xếp giờ |
| POST | `/api/v1/expert/consultation-requests/{id}/complete` | EXPERT | Đánh dấu đã tư vấn xong |
| GET | `/api/v1/expert/reviews?rating=&from=&to=&sort=&has_comment=` | EXPERT | Đánh giá dành cho mình |
| GET | `/api/v1/admin/consultation-requests?status=&q=` | ADMIN | Toàn bộ yêu cầu, mặc định COMPLETED |

Quản lý hồ sơ chuyên gia (`/api/v1/admin/experts`) nằm ngoài phạm vi tài liệu này.

## User xem lịch chuyên gia

```
GET /api/v1/experts/{userId}/availability?date=2026-10-05
```

```json
{
  "date": "2026-10-05",
  "day_off": false,
  "slots": [
    { "start_time": "08:00:00", "end_time": "08:30:00", "available": true },
    { "start_time": "08:30:00", "end_time": "09:00:00", "available": false, "reason": "CLOSED" },
    { "start_time": "09:00:00", "end_time": "09:30:00", "available": false, "reason": "BOOKED" }
  ]
}
```

Mảng `slots` luôn có đúng 24 phần tử. `reason` chỉ xuất hiện khi `available: false`.

## User đặt lịch

```json
POST /api/v1/consultation-requests
{
  "assignment_type": "DIRECT",
  "expert_user_id": "b71f…",
  "slot_date": "2026-10-05",
  "start_time": "09:00:00",
  "note": "Xin tư vấn dinh dưỡng tam cá nguyệt 2"
}
```

`RANDOM` thì chỉ cần `specialty`, không gửi chuyên gia hay khung giờ:

```json
{ "assignment_type": "RANDOM", "specialty": "HEALTH", "note": "…" }
```

Response `data`:

```json
{
  "id": "9a3c…",
  "user_id": "…", "user_display_name": "Nguyen Thi Lan",
  "expert_user_id": "b71f…", "expert_name": "BS Tam Ly",
  "specialty": "PSYCHOLOGY",
  "assignment_type": "DIRECT",
  "status": "PENDING_CONSULTATION",
  "slot": { "id": "…", "slot_date": "2026-10-05", "start_time": "09:00:00", "end_time": "09:30:00" },
  "note": "…",
  "reviewed": false, "can_review": false,
  "version": 0, "created_at": "…", "updated_at": "…"
}
```

Hủy yêu cầu sẽ **trả ô về trạng thái trống** để người khác đặt được.

## Chuyên gia xem lịch làm việc

```
GET /api/v1/expert/schedule?date=2026-10-05
```

```json
{
  "date": "2026-10-05",
  "day_off": false,
  "has_bookings": true,
  "slots": [
    { "start_time": "08:00:00", "end_time": "08:30:00", "state": "OPEN", "past": false },
    { "start_time": "08:30:00", "end_time": "09:00:00", "state": "CLOSED", "past": false },
    { "start_time": "09:00:00", "end_time": "09:30:00", "state": "BOOKED", "past": false,
      "booking": { "request_id": "9a3c…", "user_display_name": "Nguyen Thi Lan" } }
  ]
}
```

`state` là `OPEN` / `BOOKED` / `CLOSED`. Ô `BOOKED` kèm `booking` để hiện tên khách; ô này
**khóa cứng**, chuyên gia không đóng được. `has_bookings` cho biết ngày đó có buổi đã hẹn hay
chưa, dùng để cảnh báo trước khi bật nghỉ cả ngày.

## Chuyên gia đóng/mở khung giờ

```json
PUT /api/v1/expert/schedule/slot
{ "slot_date": "2026-10-05", "start_time": "12:00:00", "closed": true }
```

Trả về đúng ô vừa đổi, để FE cập nhật tại chỗ không phải tải lại cả ngày:

```json
{ "start_time": "12:00:00", "end_time": "12:30:00", "state": "CLOSED", "past": false }
```

**Idempotent** — gửi lại cùng payload cho kết quả như nhau, hợp với UI gạt-là-lưu. Gửi
`"closed": false` để mở lại.

## Chuyên gia nghỉ cả ngày

```json
PUT /api/v1/expert/schedule/day-off
{ "slot_date": "2026-10-05", "day_off": true }
```

Trả về nguyên `DayScheduleResponse` của ngày đó (cả lưới đổi trạng thái).

Nghỉ cả ngày lưu **tách riêng** khỏi các ô đóng tay, nên:

- Bật nghỉ cả ngày rồi tắt lại **không xóa mất** những khung giờ đã đóng tay trước đó.
- Các buổi **đã hẹn vẫn giữ nguyên** (vẫn nằm trong `/api/v1/expert/consultation-requests`);
  nghỉ cả ngày chỉ chặn nhận khách mới.

## Tổng hợp theo ngày

```
GET /api/v1/expert/schedule/summary?from=2026-10-05&to=2026-10-11
```

```json
[
  { "date": "2026-10-05", "open_count": 22, "booked_count": 1, "closed_count": 1, "day_off": false },
  { "date": "2026-10-06", "open_count": 0, "booked_count": 0, "closed_count": 0, "day_off": true }
]
```

`open_count` chỉ đếm ô thực sự còn đặt được (đã trừ ô quá khứ và ngày nghỉ); `booked_count` và
`closed_count` đếm theo dữ liệu thật để chuyên gia vẫn thấy khi đang nghỉ cả ngày.
Khoảng `from`–`to` tối đa 31 ngày.

## Chuyên gia tiếp nhận yêu cầu RANDOM

```json
POST /api/v1/expert/consultation-requests/{id}/accept
{ "slot_date": "2026-10-05", "start_time": "10:00:00" }
```

Chuyên gia tự chọn một khung còn trống của mình. Ai nhận trước được trước; người sau nhận
cùng một yêu cầu sẽ nhận `REQUEST_ALREADY_CLAIMED`.

## Lỗi

| Code | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_ERROR` | 422 | Thiếu trường bắt buộc, `start_time` lệch lưới, ngày ngoài 30 ngày, khoảng summary quá dài |
| `SLOT_UNAVAILABLE` | 409 | Khung giờ đã đặt / đã đóng / chuyên gia nghỉ cả ngày / đã trôi qua; hoặc chuyên gia định đóng ô đã có khách |
| `REQUEST_ALREADY_CLAIMED` | 409 | Yêu cầu RANDOM đã được chuyên gia khác nhận |
| `INVALID_CONSULTATION_STATE` | 409 | Hủy/hoàn tất yêu cầu ở trạng thái không cho phép |
| `REVIEW_NOT_ALLOWED` | 409 | Đánh giá khi buổi tư vấn chưa `COMPLETED` |
| `REVIEW_ALREADY_EXISTS` | 409 | Đã đánh giá buổi tư vấn này rồi |
| `RESOURCE_NOT_FOUND` | 404 | Không tồn tại, hoặc không phải của mình |
| `FORBIDDEN` | 403 | Tự đặt lịch với chính mình, chuyên khoa không khớp, tài khoản chưa phải hồ sơ chuyên gia |

Chống đặt trùng dựa trên unique `(expert_user_id, slot_date, start_time)` ở tầng DB, nên hai
lượt đặt song song cùng một ô thì chắc chắn một lượt nhận `SLOT_UNAVAILABLE`.
