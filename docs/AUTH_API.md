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

## Local/dev admin bootstrap

Admin bootstrap is disabled by default and must be enabled explicitly through environment configuration. Do not commit the real password or `.env` file.

```properties
NUTRIMOM_ADMIN_USER_ENABLED=true
NUTRIMOM_ADMIN_PHONE=0900000001
NUTRIMOM_ADMIN_PASSWORD=choose-a-local-secret
NUTRIMOM_ADMIN_DISPLAY_NAME=NutriMom Admin
```

On startup, the bootstrap uses the existing `users` and `user_roles` tables:

- If the configured user already exists, bootstrap sets it to `ACTIVE`, adds the `ADMIN` role, and synchronizes its password with `NUTRIMOM_ADMIN_PASSWORD`. This makes credential changes predictable on the next startup.
- If the phone does not exist, an active account with role `ADMIN` is created. The password must contain 8-72 characters, including at least one letter and one digit.
- Set `NUTRIMOM_ADMIN_USER_ENABLED=false` outside local/dev. The default is already `false`.

The bootstrap is not a separate authentication flow. Admins log in through the existing endpoint:

## POST `/auth/login` for admin

```json
{
  "phone": "0900000001",
  "password": "<local-admin-password>",
  "device_id": "admin-web"
}
```

The normal authentication envelope is returned. The user object and access JWT both contain the admin role:

```json
{
  "data": {
    "access_token": "eyJ...",
    "expires_in": 900,
    "refresh_token": "opaque-token",
    "refresh_expires_in": 2592000,
    "token_type": "Bearer",
    "user": {
      "id": "uuid",
      "phone": "+84900000001",
      "display_name": "NutriMom Admin",
      "roles": ["ADMIN"],
      "status": "ACTIVE",
      "created_at": "2026-08-10T12:00:00Z"
    }
  },
  "meta": {}
}
```

Relevant access-token claim:

```json
{"sub": "uuid", "phone": "+84900000001", "roles": ["ADMIN"], "type": "access"}
```

## GET `/admin/me`

Returns the authenticated admin profile using the standard response envelope.

```http
GET /api/v1/admin/me
Authorization: Bearer eyJ...
```

```json
{
  "data": {
    "id": "uuid",
    "phone": "+84900000001",
    "display_name": "NutriMom Admin",
    "roles": ["ADMIN"],
    "status": "ACTIVE",
    "created_at": "2026-08-10T12:00:00Z"
  },
  "meta": {
    "request_id": "uuid",
    "server_time": "2026-08-10T12:00:00Z"
  }
}
```

- Missing or invalid bearer token: `401 UNAUTHORIZED`.
- Authenticated account without role `ADMIN`: `403 FORBIDDEN`.

## Flutter

- Lưu token bằng `flutter_secure_storage`, không dùng SharedPreferences.
- Chỉ gửi refresh token vào refresh/logout.
- Khi nhiều request gặp 401, chỉ chạy một refresh request.
- Không log password hoặc token.
