# Contact API (hộp thư hỗ trợ)

User gửi thắc mắc (chính sách, cách dùng app/web, tài khoản…). Admin mở chi tiết để lấy
thông tin liên hệ, **gọi điện trực tiếp** cho user giải đáp, rồi đánh dấu hoàn tất.

Tất cả endpoint cần JWT (`Authorization: Bearer <token>`). JSON dùng snake_case, bọc trong
`ApiResponse` (`data`, `meta`); lỗi trả `error.code` theo [error-codes.md](error-codes.md).

## Trạng thái

```
PENDING ──(admin complete)──▶ COMPLETED
   └────(user cancel)───────▶ CANCELLED
```

| Status | Hiển thị gợi ý |
|---|---|
| `PENDING` | Đang chờ |
| `COMPLETED` | Đã hoàn tất |
| `CANCELLED` | Đã huỷ |

Chủ đề (`topic`): `POLICY` (Chính sách), `APP_USAGE` (Cách sử dụng), `ACCOUNT` (Tài khoản), `OTHER` (Khác).

## Endpoints

| Method | Path | Quyền | Ghi chú |
|---|---|---|---|
| POST | `/api/v1/contact-requests` | User | Tạo yêu cầu, 201 |
| GET | `/api/v1/contact-requests?page=1&pageSize=20` | User | Lịch sử của mình, mới nhất trước |
| GET | `/api/v1/contact-requests/{id}` | User | Chi tiết (của người khác → 404) |
| POST | `/api/v1/contact-requests/{id}/cancel` | User | Chỉ khi `PENDING` |
| GET | `/api/v1/admin/contact-requests?status=&topic=&q=&page=1&pageSize=20` | ADMIN | Hộp thư, mới nhất trước |
| GET | `/api/v1/admin/contact-requests/{id}` | ADMIN | Chi tiết + thông tin liên hệ user |
| POST | `/api/v1/admin/contact-requests/{id}/complete` | ADMIN | Chỉ khi `PENDING` |

`page` bắt đầu từ 1, `pageSize` 1–100. Admin list: `status`, `topic` bỏ trống = tất cả;
`q` tìm theo tên hiển thị hoặc số điện thoại user (không phân biệt hoa thường).

## Tạo yêu cầu

```json
POST /api/v1/contact-requests
{ "topic": "POLICY", "message": "Chính sách hoàn tiền thế nào?" }
```

`message` bắt buộc, tối đa 2000 ký tự (server tự trim). Response `data`:

```json
{
  "id": "5d0c…",
  "topic": "POLICY",
  "message": "Chính sách hoàn tiền thế nào?",
  "status": "PENDING",
  "created_at": "2026-09-25T03:40:00Z",
  "completed_at": null,
  "cancelled_at": null
}
```

Danh sách trả `data` dạng `{ "items": [...], "page": 1, "page_size": 20, "total_items": 3, "total_pages": 1 }`.

## Admin

Mỗi item trong list:

```json
{
  "id": "5d0c…",
  "topic": "APP_USAGE",
  "message_preview": "Không biết dùng nhật ký thai kỳ.",
  "status": "PENDING",
  "user_id": "a1b2…",
  "user_display_name": "Nguyen Thi Lan",
  "user_phone": "0913000021",
  "created_at": "…",
  "completed_at": null
}
```

`message_preview` cắt 120 ký tự (thêm `…`). Chi tiết / kết quả `complete`:

```json
{
  "id": "5d0c…",
  "topic": "APP_USAGE",
  "message": "Không biết dùng nhật ký thai kỳ.",
  "status": "COMPLETED",
  "created_at": "…",
  "completed_at": "…",
  "completed_by": "<admin user id>",
  "cancelled_at": null,
  "user": {
    "id": "a1b2…",
    "display_name": "Nguyen Thi Lan",
    "phone": "0913000021",
    "email": "lan@example.com",
    "gender": "FEMALE",
    "date_of_birth": "1995-04-12"
  }
}
```

## Lỗi

| Code | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_ERROR` | 422 | Thiếu `topic`, `message` rỗng/quá dài (`error.fields`) |
| `CONTACT_REQUEST_LIMIT_REACHED` | 409 | User đã có 3 yêu cầu `PENDING` |
| `INVALID_CONTACT_REQUEST_STATE` | 409 | Huỷ/hoàn tất yêu cầu không còn `PENDING` |
| `RESOURCE_NOT_FOUND` | 404 | Không tồn tại hoặc không phải của mình |
| `FORBIDDEN` | 403 | Không phải ADMIN gọi `/api/v1/admin/**` |
