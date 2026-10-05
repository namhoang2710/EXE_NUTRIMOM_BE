# Notification API (thông báo, push token và activity feed)

Triển khai spec mục 18 "Notifications và activity feed": đăng ký push token của thiết bị,
hộp thông báo in-app phân trang bằng cursor, đánh dấu đã đọc (idempotent) và dòng hoạt động
mà người gọi có quyền xem.

Tất cả endpoint cần JWT (`Authorization: Bearer <token>`). JSON dùng snake_case, bọc trong
`ApiResponse` (`data`, `meta`); lỗi trả `error.code` theo [error-codes.md](error-codes.md).
Module này **không thêm error code mới** — chỉ dùng `VALIDATION_ERROR` và `RESOURCE_NOT_FOUND`.

## Endpoints

| Method | Path | Quyền | Ghi chú |
|---|---|---|---|
| POST | `/api/v1/devices` | User | Upsert theo `device_id`; 201 nếu tạo mới, 200 nếu cập nhật |
| DELETE | `/api/v1/devices/{device_id}` | User | Gỡ khi đăng xuất; idempotent → 204 |
| GET | `/api/v1/notifications?cursor=&limit=20&unreadOnly=false` | User | Mới nhất trước, cursor |
| GET | `/api/v1/notifications/unread-count` | User | Chỉ số chưa đọc, cho chuông thông báo |
| POST | `/api/v1/notifications/{id}/read` | User | Idempotent |
| POST | `/api/v1/notifications/read-all` | User | Idempotent, trả số dòng vừa đổi |
| GET | `/api/v1/activity-feed?cursor=&limit=20` | User | Của mình + phần được chia sẻ |

`limit` từ 1 đến 50, mặc định 20. `POST /api/v1/devices` bị rate-limit 20 lần/phút mỗi user.

## 1. Đăng ký thiết bị nhận push

```json
POST /api/v1/devices
{
  "platform": "ANDROID",
  "push_token": "fcm-token-…",
  "device_id": "stable-installation-id",
  "app_version": "1.2.0"
}
```

`platform`: `ANDROID` | `IOS` | `WEB`. `device_id` là id cài đặt ổn định của app (cùng khái niệm
với header `X-Device-Id`), tối đa 100 ký tự. Gọi lại với cùng `device_id` sẽ **cập nhật** chứ
không tạo thêm dòng, nên app cứ gọi mỗi lần khởi động hoặc mỗi khi FCM xoay token.

Response `data`:

```json
{
  "id": "0f3a…",
  "device_id": "stable-installation-id",
  "platform": "ANDROID",
  "app_version": "1.2.0",
  "last_seen_at": "2026-09-29T04:10:00Z"
}
```

Không có trường `push_token` trong response — client vừa gửi lên nên không cần nhận lại.

**Bảo mật token.** Token được lưu mã hoá AES-256-GCM (cột `push_token_cipher`), kèm SHA-256 của
token (cột `push_token_hash`) chỉ để tra cứu vì ciphertext dùng IV ngẫu nhiên nên không tất định.
Key lấy từ biến môi trường `NUTRIMOM_PUSH_TOKEN_KEY` (base64 của 32 byte, sinh bằng
`openssl rand -base64 32`); thiếu key thì ứng dụng không khởi động. Token không xuất hiện trong log.

Một push token chỉ thuộc về một tài khoản: nếu user B đăng ký đúng token mà user A đang giữ
(thường là hai tài khoản trên cùng một máy), bản ghi của A bị gỡ. Nếu không làm vậy, chiếc máy đó
sẽ nhận push của tài khoản đã đăng xuất.

```
DELETE /api/v1/devices/{device_id}   → 204
```

Gọi khi đăng xuất. Gọi lại hoặc gọi với `device_id` không tồn tại vẫn trả 204.

## 2. Hộp thông báo

```
GET /api/v1/notifications?limit=20&unreadOnly=false
```

```json
{
  "data": {
    "items": [
      {
        "id": "8c21…",
        "type": "CONTACT",
        "title": "Yêu cầu hỗ trợ đã được xử lý",
        "body": "Đội hỗ trợ đã hoàn tất yêu cầu của bạn. Mở ứng dụng để xem lại.",
        "deep_link": "nutrimom://contact-requests/5d0c…",
        "source_type": "CONTACT_REQUEST",
        "source_id": "5d0c…",
        "read_at": null,
        "created_at": "2026-09-29T04:12:00Z"
      }
    ],
    "next_cursor": "MjAyNi0wOS0yOVQwNDoxMjowMFp8OGMyMQ",
    "has_more": true
  }
}
```

`type`: `CONSULTATION` | `CONTACT` | `FAMILY` | `REMINDER` | `SYSTEM`.
`read_at` null nghĩa là chưa đọc (trường này bị lược khỏi JSON khi null).

**Phân trang.** Truyền `next_cursor` của lần trước vào `cursor` để lấy trang kế. Cursor là chuỗi
opaque mã hoá cặp `(created_at, id)` của dòng cuối trang — nên khi có thông báo mới chen vào giữa
hai lần gọi, trang sau vẫn không bị lặp hay nhảy sót (spec mục 22). Cursor sai định dạng trả 422
`VALIDATION_ERROR`, không âm thầm quay về trang đầu. `has_more = false` là hết.

```
POST /api/v1/notifications/{id}/read     → 200, trả về thông báo đã cập nhật
POST /api/v1/notifications/read-all      → 200 {"updated": 3}
```

Cả hai đều idempotent: gọi lại `{id}/read` giữ nguyên `read_at` của lần đầu, `read-all` lần hai
trả `{"updated": 0}`. Thông báo của người khác trả **404** `RESOURCE_NOT_FOUND` (không phải 403,
để không lộ việc id đó có tồn tại).

```
GET /api/v1/notifications/unread-count   → 200
```

```json
{
  "data": { "count": 3 }
}
```

Chỉ đếm thông báo chưa đọc của chính người gọi. Đây là bản nhẹ của
`unread_notification_count` trong `GET /api/v1/dashboard/mom`, dành cho chuông thông báo nằm trên
mọi trang: FE poll endpoint này thay vì kéo cả payload dashboard chỉ để lấy một con số.
Endpoint không nằm trong `app.security.rate-limit.routes` nên poll theo chu kỳ (60 giây) không bị
chặn; nếu sau này cần siết thì thêm route rule riêng.

## 3. Activity feed

```
GET /api/v1/activity-feed?limit=20
```

```json
{
  "data": {
    "items": [
      {
        "id": "a71f…",
        "type": "FAMILY_TASK_ASSIGNED",
        "title": "Một việc mới đã được giao trong nhóm gia đình",
        "actor_user_id": "3b9e…",
        "pregnancy_id": "77c0…",
        "created_at": "2026-09-29T04:20:00Z"
      }
    ],
    "next_cursor": null,
    "has_more": false
  }
}
```

`type`: `CONSULTATION_ACCEPTED` | `CONSULTATION_COMPLETED` | `CONSULTATION_CANCELLED` |
`CONTACT_COMPLETED` | `FAMILY_TASK_ASSIGNED` | `FAMILY_TASK_COMPLETED`.
Cursor giống hệt `/notifications`.

**Ai thấy gì.** Người gọi thấy hoạt động của chính mình (mọi mức), cộng hoạt động mức `FAMILY` của
những thai kỳ mà họ là thành viên gia đình ACTIVE **và** có scope `ACTIVITY_FEED`. Bỏ scope ở
`PATCH /api/v1/family-members/{id}` là mất quyền xem ngay lần gọi kế tiếp.

**Không rò dữ liệu y tế.** Feed cố ý không có trường free-text nào: `title` là chuỗi cố định do
backend dựng sẵn, không bao giờ ghép nội dung hồ sơ, câu hỏi tư vấn hay tin nhắn vào. Mọi hoạt
động chạm tới y tế/tư vấn được ghi ở mức `OWNER_ONLY` nên không lọt sang feed của thành viên khác.
Dashboard partner (`GET /api/v1/dashboard/partner` → `data.activity_feed`) dùng đúng quy tắc này.

## 4. Thông báo được sinh ra từ đâu

| Sự kiện | Người nhận | `type` | Activity |
|---|---|---|---|
| User tạo yêu cầu RANDOM | **mọi chuyên gia ACTIVE cùng chuyên khoa** | `CONSULTATION` | — |
| User đặt lịch DIRECT | Chuyên gia được chọn | `CONSULTATION` | — |
| Chuyên gia tiếp nhận yêu cầu RANDOM | Người đặt | `CONSULTATION` | `CONSULTATION_ACCEPTED`, OWNER_ONLY |
| Chuyên gia hoàn tất buổi tư vấn | Người đặt | `CONSULTATION` | `CONSULTATION_COMPLETED`, OWNER_ONLY |
| User huỷ yêu cầu đã có chuyên gia | Chuyên gia | `CONSULTATION` | `CONSULTATION_CANCELLED`, OWNER_ONLY |
| User gửi yêu cầu hỗ trợ | **mọi tài khoản ADMIN đang hoạt động** | `CONTACT` | — |
| Admin hoàn tất yêu cầu hỗ trợ | Người gửi | `CONTACT` | `CONTACT_COMPLETED`, OWNER_ONLY |
| Giao việc trong nhóm (tạo mới hoặc giao lại) | Người được giao | `FAMILY` | `FAMILY_TASK_ASSIGNED`, FAMILY |
| Việc trong nhóm chuyển `COMPLETED` | Chủ nhóm (mẹ bầu) | `FAMILY` | `FAMILY_TASK_COMPLETED`, FAMILY |

**Mốc giờ hẹn nằm ngay trong body.** Với yêu cầu RANDOM, chuyên gia mới là người chọn khung giờ lúc
accept, nên thông báo gửi cho user ghi rõ *"Buổi tư vấn của bạn được xếp lúc 09:00 ngày 02/10/2026"* —
không bắt user mở app mới biết mình hẹn lúc nào, tránh lỡ buổi tư vấn do người khác sắp xếp.
Thông báo cho chuyên gia khi có đặt lịch DIRECT cũng vậy.

**Deep link khác nhau theo vai.** Thông báo cho user trỏ `nutrimom://consultations/{id}`, cho chuyên
gia trỏ `nutrimom://expert/consultations/{id}` (riêng broadcast RANDOM trỏ vào hàng chờ
`nutrimom://expert/consultations` vì yêu cầu chưa thuộc về ai), cho admin trỏ
`nutrimom://admin/contact-requests/{id}`.

**Không báo thừa:** tự giao việc cho mình, tự bấm hoàn thành việc của mình, tự đặt lịch DIRECT, hay
sửa việc mà không đổi người phụ trách lẫn trạng thái — đều không sinh thông báo. Huỷ một yêu cầu
RANDOM còn đang `PENDING_EXPERT` cũng không báo ai, vì chưa chuyên gia nào nhận nó.

**Fan-out.** Hai sự kiện phát cho cả một vai trò (broadcast chuyên khoa, và yêu cầu hỗ trợ mới) ghi
một dòng notification cho từng người nhận. Số chuyên gia/admin ở quy mô hiện tại là nhỏ; nếu sau này
lớn lên thì đây là chỗ đầu tiên cần xem lại.

## 5. Tuỳ chọn người dùng và quiet hours

Thông báo **luôn** được ghi vào hộp in-app. Riêng việc đẩy push bị bỏ qua khi
`GET/PATCH /api/v1/users/me/preferences` có `notification_enabled = false`, `push_enabled = false`,
hoặc thời điểm hiện tại rơi vào `quiet_hours` (tính theo `timezone` của user, hỗ trợ khoảng vắt
qua nửa đêm như 22:00 → 06:30).

## 6. Gửi push thật

Hiện `PushSender` chỉ có bản mặc định `LoggingPushSender` ghi log, giống cách
`LocalOtpDeliveryGateway` thay cho nhà cung cấp SMS. Để nối FCM: thêm dependency Firebase Admin,
viết một `FcmPushSender implements PushSender` đánh dấu `@Primary`. Không phải sửa gì khác trong
module — push được gọi sau khi transaction commit, và lỗi gửi chỉ ghi log chứ không làm hỏng request.

Đặt `NUTRIMOM_PUSH_ENABLED=false` để tắt hẳn việc gửi push mà vẫn giữ hộp thông báo in-app.
