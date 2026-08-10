# Authentication API v1

Base URL: `http://localhost:8080/api/v1`. JSON dùng `snake_case`, thời gian ISO-8601 UTC. Response có header `X-Request-Id`.

## Envelope

```json
{"data": {}, "meta": {"request_id": "uuid", "server_time": "2026-08-10T12:00:00Z"}}
```

```json
{"error": {"code": "INVALID_OTP", "message": "Mã OTP không chính xác.", "fields": {}, "retryable": false, "request_id": "uuid"}}
```

## POST `/auth/otp/request`

Đăng ký:

```json
{
  "phone": "0905551234",
  "purpose": "REGISTER",
  "accepted_terms": true,
  "device_id": "stable-installation-id"
}
```

Đăng nhập dùng `purpose: "LOGIN"`, không cần `accepted_terms`.

```json
{
  "data": {
    "challenge_id": "uuid",
    "masked_phone": "+84******234",
    "delivery_channel": "DEBUG",
    "expires_in": 300,
    "resend_after": 45,
    "debug_code": "629104"
  },
  "meta": {}
}
```

`debug_code` chỉ dùng local. Lỗi: `ACCOUNT_NOT_FOUND`, `PHONE_ALREADY_EXISTS`, `TERMS_NOT_ACCEPTED`, `OTP_RESEND_TOO_SOON`, `OTP_REQUEST_IN_PROGRESS`.

## POST `/auth/otp/verify`

```json
{
  "challenge_id": "uuid",
  "code": "629104",
  "device_id": "stable-installation-id",
  "display_name": "Nguyễn An"
}
```

```json
{
  "data": {
    "new_user": true,
    "authentication": {
      "access_token": "eyJ...",
      "expires_in": 900,
      "refresh_token": "opaque-token",
      "refresh_expires_in": 2592000,
      "token_type": "Bearer",
      "user": {}
    }
  },
  "meta": {}
}
```

Lỗi: `INVALID_OTP`, `OTP_EXPIRED`, `OTP_ATTEMPTS_EXCEEDED`, `OTP_CHALLENGE_USED`, `OTP_DEVICE_MISMATCH`.

## POST `/auth/login`

```json
{"phone": "0901234567", "password": "NutriMom@123", "device_id": "device-id"}
```

Trả access/refresh token. Sai thông tin trả `401 INVALID_CREDENTIALS`.

## POST `/auth/refresh`

```json
{"refresh_token": "opaque-token", "device_id": "device-id"}
```

Backend thu hồi token cũ và cấp cặp mới. Flutter phải lưu đè cả hai token nguyên tử.

## POST `/auth/logout`

```json
{"refresh_token": "opaque-token"}
```

Idempotent, luôn trả `logged_out: true`.

## GET `/auth/me`

```http
Authorization: Bearer eyJ...
```

## Flutter

- Lưu token bằng `flutter_secure_storage`, không dùng SharedPreferences.
- Chỉ gửi refresh token vào refresh/logout.
- Khi nhiều request gặp 401, chỉ chạy một refresh request.
- Không log password hoặc token.
