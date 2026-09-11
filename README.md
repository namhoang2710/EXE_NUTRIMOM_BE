# NutriMom Backend

Backend Spring Boot cho ứng dụng chăm sóc sức khỏe mẹ bầu NutriMom, dùng Microsoft SQL Server, Flyway, Spring Security JWT và Swagger/OpenAPI.

## Thành phần đã có

- Java 17, Maven Wrapper, Spring Boot 4.1.
- SQL Server + JPA/Hibernate; Flyway quản lý schema.
- Đăng ký/đăng nhập bằng OTP động 6 số; password được giữ cho tương thích/quản trị.
- Access JWT 15 phút, refresh token opaque 30 ngày và refresh-token rotation.
- OTP HMAC-SHA256, hết hạn 5 phút, tối đa 5 lần nhập sai, cooldown gửi lại 45 giây.
- BCrypt cost 12, role-based authorization, request ID và lỗi JSON thống nhất.
- Swagger UI, Actuator health, Prometheus, Redis và WebSocket dependencies.

## SQL Server local

| Hạng mục | Giá trị |
|---|---|
| Database | `NutriMomDB` |
| Collation | `Vietnamese_100_CI_AI_SC_UTF8` |
| Schema | `app` |
| Runtime login | `nutrimom_app` |
| Migration login | `nutrimom_migrator` |
| TCP port | `1433` |

Hai tài khoản được tách quyền: ứng dụng chỉ đọc/ghi dữ liệu; tài khoản migrator thay đổi schema bằng Flyway. Không dùng `sa` để chạy ứng dụng.

Nếu SQL Server chưa bật TCP/IP, mở PowerShell bằng **Run as administrator**:

```powershell
Set-Location 'C:\Users\ADMIN\Desktop\EXE\EXE_BE'
powershell -ExecutionPolicy Bypass -File .\scripts\enable-sqlserver-tcp.ps1
Test-NetConnection localhost -Port 1433
```

Tạo database trên máy mới:

```powershell
sqlcmd -S localhost -E -C -b `
  -i .\scripts\sqlserver\00_create_database.sql `
  -v AppPassword="your-strong-app-password" MigratorPassword="your-strong-migrator-password"
```

Script idempotent, không xóa dữ liệu hiện có.

## Chạy backend

```powershell
Set-Location 'C:\Users\ADMIN\Desktop\EXE\EXE_BE'
Copy-Item .env.example .env
# Dien cac mat khau SQL Server va secret local trong .env, khong commit file nay.
.\mvnw.cmd spring-boot:run
```

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Health: `http://localhost:8080/actuator/health`

Local trả OTP ngẫu nhiên trong `debug_code` để thử Swagger mà chưa mua SMS. Production phải thay `OtpDeliveryGateway` bằng eSMS/VNPT/Twilio và đặt `NUTRIMOM_OTP_EXPOSE_DEBUG_CODE=false`. Flutter hiện có 4 ô OTP nên khi nối API cần đổi thành 6 ô.

Tài khoản password demo khi `NUTRIMOM_DEMO_USER_ENABLED=true`:

- `0901234567`
- `NutriMom@123`

## Endpoint xác thực

| Method | Endpoint | Mục đích |
|---|---|---|
| POST | `/api/v1/auth/otp/request` | Tạo OTP cho `LOGIN`/`REGISTER` |
| POST | `/api/v1/auth/otp/verify` | Xác minh OTP, cấp token |
| POST | `/api/v1/auth/register` | Đăng ký password |
| POST | `/api/v1/auth/login` | Đăng nhập password |
| POST | `/api/v1/auth/refresh` | Xoay vòng refresh token |
| POST | `/api/v1/auth/logout` | Thu hồi refresh token |
| GET | `/api/v1/auth/me` | Lấy người dùng hiện tại |
| GET | `/api/v1/admin/me` | Lấy admin hiện tại; yêu cầu role `ADMIN` |

Chi tiết contract: [docs/AUTH_API.md](docs/AUTH_API.md).

## Test và build

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean verify
```

Integration test dùng H2 chế độ SQL Server, không ghi/xóa dữ liệu `NutriMomDB`. Mỗi thay đổi schema tiếp theo phải thêm migration mới như `V2__create_pregnancy_profiles.sql`.
