# Security khung (Task 21)

Tài liệu quy ước bảo mật dùng chung của NutriMom backend (spec mục 21 "Security và retention",
mục 22 "contract test IDOR"). Hai cơ chế: **rate-limit** và **row-level authorization**.

> Ghi chú: audit log (spec mục 19) đã cân nhắc nhưng lược bỏ khỏi phạm vi hiện tại để giữ code gọn;
> có thể bổ sung lại sau nếu cần.

## 1. Rate-limit (giới hạn tần suất)

- **Cách đếm:** token bucket **trong RAM** (`InMemoryRateLimiterStore`), bọc sau interface
  `RateLimiterStore` (`vn.nutrimom.common.ratelimit`). Muốn đếm phân tán qua Redis: thêm một
  implementation `RateLimiterStore` mới, không phải sửa filter/service.
- **Filter:** `vn.nutrimom.security.RateLimitFilter`, được `SecurityConfig` khởi tạo và gắn *trước*
  `AuthorizationFilter` (sau khi xác thực). Request đã đăng nhập khóa theo `userId` (claim `sub`);
  login/OTP (chưa xác thực) khóa theo IP (+ `X-Device-Id` nếu có) — xem `RateLimitKeyResolver`.
- **Khi bị chặn:** HTTP `429` với envelope chuẩn spec 1.2 `{"error":{"code":"RATE_LIMITED",...}}`
  (phát qua `SecurityErrorWriter`) kèm header `Retry-After` (giây).
- **Cấu hình:** `app.security.rate-limit` trong `application.yml`. Bật/tắt bằng
  `NUTRIMOM_RATE_LIMIT_ENABLED` (mặc định `true`; integration test đặt `false`).

  | policy  | capacity | window | route                       |
  |---------|----------|--------|-----------------------------|
  | login   | 10       | 1 phút | `/api/v1/auth/login`        |
  | otp     | 5        | 1 phút | `/api/v1/auth/otp/**`       |
  | default | 100      | 1 phút | (chưa gắn route — sẵn để dùng) |

  > Ngưỡng là **lựa chọn vận hành** — spec mục 21 chỉ liệt kê luồng cần giới hạn (OTP/login/scan/chat/upload),
  > không cho con số. Thêm luồng mới = thêm `policies` + `routes` trong config, không build lại.
  > Gắn `default` cho toàn hệ thống: thêm route `pattern: /api/v1/**, policy: default`.

## 2. Row-level authorization (chống IDOR)

Quy ước (spec mục 21 "row-level ở service, không chỉ ẩn nút FE"; mục 1.1 "ID public dùng UUID"):

- Mọi truy vấn tài nguyên **kèm điều kiện chủ sở hữu** (vd `findByIdAndOwnerUserId`) hoặc so sánh owner ở
  service — không tin tưởng ID từ client.
- Không sở hữu → trả **`RESOURCE_NOT_FOUND` (404)**, KHÔNG phải 403, để không lộ tài nguyên có tồn tại.
- Tài nguyên chia sẻ nhưng thiếu quyền → **`SHARING_SCOPE_REQUIRED` (403)** (theo `FamilyScope`).
- Helper dùng chung: `vn.nutrimom.common.security.AccessGuard`
  (`requireOwned` → 404, `requireOwnership` → 404, `requireScope` → 403).
- **Kiểm thử:** `PregnancyIntegrationTest#pregnancyReadAndWriteAreRestrictedToOwner` chứng minh IDOR
  end-to-end (user A không đọc/sửa được thai kỳ của user B → 404); `AccessGuardTest` chốt hợp đồng helper.

## Nguyên tắc "không log dữ liệu nhạy cảm"

Không ghi token, OTP, password, nội dung hồ sơ y tế, hay URL có chữ ký vào log/response.
