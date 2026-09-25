# NutriMom — Error Code Contract (spec mục 20)

> Nguồn sự thật: `vn.nutrimom.common.exception.ErrorCode`. Mọi nơi phát lỗi phải dùng hằng trong enum này, **không** viết literal string. Test `ErrorCatalogTest` khoá danh sách + HTTP status của nhóm bắt buộc — đừng đổi ngầm.

## Error envelope (spec 1.2)

Mọi lỗi trả về theo đúng shape sau (serialize snake_case; `request_id` lấy từ `X-Request-Id` / MDC):

```json
{
  "error": {
    "code": "VERSION_CONFLICT",
    "message": "Dữ liệu đã được cập nhật ở nơi khác. Vui lòng tải lại và thử lại.",
    "fields": { "display_name": "Không được để trống" },
    "retryable": false,
    "request_id": "d2d39392-d39b-4e31-a241-782a087e8f18"
  }
}
```

- `fields`: chỉ có với lỗi validation (map field → message); các lỗi khác trả `{}`.
- `retryable`: client có thể thử lại (rate-limit, sự cố tạm thời) hay không.
- Lớp security (401/403) phát cùng shape này qua `SecurityErrorWriter` (chạy trước controller advice).

## Cách dùng (cho dev)

```java
// đơn giản (message mặc định của enum)
throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE);
// kèm message tuỳ biến
throw new BusinessException(ErrorCode.VERSION_CONFLICT, "Bản ghi đã thay đổi, tải lại giúp.");
// kèm field detail
throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Dữ liệu chưa hợp lệ",
        Map.of("email", "Email không đúng định dạng"));
```

HTTP status và cờ `retryable` đã gắn sẵn trong enum — không truyền lại.

## Danh mục code bắt buộc (spec mục 20)

| Code | HTTP | retryable | Ý nghĩa | Trạng thái |
|---|---|---|---|---|
| VALIDATION_ERROR | 422 | false | Dữ liệu gửi lên không hợp lệ (kèm `fields`) | dùng |
| UNAUTHORIZED | 401 | false | Chưa xác thực / phiên không hợp lệ | dùng |
| FORBIDDEN | 403 | false | Không đủ quyền | dùng |
| RESOURCE_NOT_FOUND | 404 | false | Không tìm thấy tài nguyên | dùng |
| VERSION_CONFLICT | 409 | false | Optimistic lock — bản ghi đã bị sửa nơi khác | dùng |
| IDEMPOTENCY_CONFLICT | 409 | false | Trùng Idempotency-Key với thao tác khác | **reserved** (07/13/15/17) |
| RATE_LIMITED | 429 | true | Vượt giới hạn tần suất | **reserved** (task 21) |
| OTP_EXPIRED | 401 | false | Mã OTP hết hạn | dùng |
| INVALID_OTP | 401 | false | Mã OTP sai | dùng |
| OTP_ATTEMPTS_EXCEEDED | 401 | false | Sai OTP quá số lần | dùng |
| OTP_CHALLENGE_USED | 401 | false | Phiên OTP đã dùng/không còn hiệu lực | dùng |
| OTP_DEVICE_MISMATCH | 401 | false | OTP không thuộc thiết bị | dùng |
| INVALID_REFRESH_TOKEN | 401 | false | Refresh token sai/đã xoay | dùng |
| FILE_TOO_LARGE | 413 | false | Tệp vượt dung lượng | handler sẵn; throw ở module 07 |
| UNSUPPORTED_FILE_TYPE | 415 | false | Định dạng tệp không hỗ trợ | **reserved** (07) |
| MALWARE_DETECTED | 422 | false | Tệp nghi chứa mã độc | **reserved** (07) |
| UPLOAD_NOT_COMPLETE | 409 | false | Tệp chưa hoàn tất upload | **reserved** (07) |
| SLOT_UNAVAILABLE | 409 | false | Khung giờ tư vấn đã bị đặt | dùng (consultation) |
| QUOTA_EXCEEDED | 429 | false | Hết hạn mức (scan/subscription) | **reserved** (15/17) |
| SCAN_FAILED | 422 | false | Xử lý ảnh/tài liệu thất bại | **reserved** (15) |
| SHARING_SCOPE_REQUIRED | 403 | false | Chưa được cấp scope chia sẻ | dùng |
| CONTENT_NOT_REVIEWED | 409 | false | Nội dung chưa được duyệt | **reserved** (12) |

## Code mở rộng theo domain (đang dùng, ngoài danh sách bắt buộc)

| Code | HTTP | retryable | Module |
|---|---|---|---|
| PHONE_ALREADY_EXISTS | 409 | false | auth |
| INVALID_PHONE | 422 | false | auth |
| TERMS_NOT_ACCEPTED | 422 | false | auth |
| OTP_RESEND_TOO_SOON | 429 | true | auth |
| OTP_REQUEST_IN_PROGRESS | 429 | true | auth |
| OTP_PROVIDER_NOT_CONFIGURED | 503 | true | auth |
| INVALID_CREDENTIALS | 401 | false | auth |
| OTP_CHALLENGE_NOT_FOUND | 401 | false | auth |
| ACCOUNT_NOT_FOUND | 404 | false | auth |
| ACCOUNT_UNAVAILABLE | 401 | false | auth |
| OTP_REAUTHENTICATION_MISMATCH | 401 | false | auth |
| REAUTHENTICATION_REQUIRED | 401 | false | user |
| INVALID_REAUTHENTICATION | 401 | false | user |
| INVALID_INVITATION_TOKEN | 404 | false | family |
| INVITATION_ALREADY_USED | 409 | false | family |
| INVITATION_EXPIRED | 422 | false | family |
| INVITATION_TARGET_MISMATCH | 403 | false | family |
| FAMILY_GROUP_NOT_FOUND | 404 | false | family |
| OWNER_ALREADY_IN_GROUP | 409 | false | family |
| FAMILY_MEMBER_EXISTS | 409 | false | family |
| PREGNANCY_NOT_ACTIVE | 409 | false | family/pregnancy |
| ACTIVE_PREGNANCY_NOT_FOUND | 404 | false | family/dashboard |
| PREGNANCY_ARCHIVED | 409 | false | pregnancy |
| ACTIVE_PREGNANCY_EXISTS | 409 | false | pregnancy |
| ARTICLE_NOT_FOUND | 404 | false | knowledge/CMS |
| SLUG_ALREADY_EXISTS | 409 | false | knowledge/CMS |
| DB_SAVE_FAILED | 500 | true | knowledge/CMS |
| R2_UPLOAD_FAILED | 502 | true | knowledge (media/R2 storage) |
| INVALID_FILE_TYPE | 422 | false | knowledge (image upload) |
| INVALID_IMAGE_DIMENSIONS | 422 | false | knowledge (image upload) |
| IMAGE_PROCESSING_FAILED | 422 | false | knowledge (image upload) |
| IMAGE_PROCESSING_BUSY | 429 | true | knowledge (image upload) |
| REQUEST_ALREADY_CLAIMED | 409 | false | consultation (yêu cầu ngẫu nhiên đã bị chuyên gia khác nhận) |
| REVIEW_ALREADY_EXISTS | 409 | false | consultation (đã đánh giá buổi tư vấn) |
| REVIEW_NOT_ALLOWED | 409 | false | consultation (chưa hoàn thành, không được đánh giá) |
| INVALID_CONSULTATION_STATE | 409 | false | consultation (thao tác sai trạng thái yêu cầu) |
| INVALID_CONTACT_REQUEST_STATE | 409 | false | contact (huỷ/hoàn tất yêu cầu hỗ trợ không còn ở PENDING) |
| CONTACT_REQUEST_LIMIT_REACHED | 409 | false | contact (user đã có 3 yêu cầu hỗ trợ đang chờ) |
| INVALID_MULTIPART | 400 | false | handler (multipart hỏng/thiếu part) |
| INVALID_CONTENT_TYPE | 415 | false | handler (Content-Type không hỗ trợ) |
| INVALID_REQUEST_PARAMETER | 400 | false | handler (query param sai kiểu/thiếu) |
| MALFORMED_JSON | 400 | false | handler (body JSON hỏng) |
| INTERNAL_ERROR | 500 | true | handler (lỗi không lường trước) |

## Ghi chú cho các nhánh sau (17–22 và module người khác)

- Code **reserved** đã có sẵn hằng trong `ErrorCode` — cứ `throw new BusinessException(ErrorCode.X, ...)`, không tự đặt tên mới.
- Cần code mới chưa có? Thêm hằng vào `ErrorCode` (kèm HTTP status + retryable + message mặc định) rồi cập nhật bảng này — đừng viết literal string ở service.
- `GlobalExceptionHandler` đã tự map các lỗi Spring phổ biến về code chuẩn: body hỏng → `MALFORMED_JSON`; query param sai kiểu/thiếu → `INVALID_REQUEST_PARAMETER` (400); body/DTO validation → `VALIDATION_ERROR` (422); route không tồn tại → `RESOURCE_NOT_FOUND`; upload quá cỡ → `FILE_TOO_LARGE`; multipart hỏng → `INVALID_MULTIPART`; Content-Type sai → `INVALID_CONTENT_TYPE`; 401/403 → `UNAUTHORIZED`/`FORBIDDEN`.
