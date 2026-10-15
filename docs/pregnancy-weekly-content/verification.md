# Kết quả kiểm thử — Pregnancy weekly content

Ngày chạy: 15/10/2026. Máy: Windows 11, OpenJDK 17.0.20.1, Maven wrapper của repo.
Mọi dữ liệu test là **tổng hợp**, không phải bài được duyệt; không test nào ghi vào DB dùng chung.

## Lệnh đã chạy

```powershell
# PostgreSQL test riêng (DB tạm nutrimom_weekly_test trên localhost, PostgreSQL 18.4)
$env:NUTRIMOM_PG_TEST_URL = "jdbc:postgresql://localhost:5432/nutrimom_weekly_test?currentSchema=app"
$env:NUTRIMOM_PG_TEST_USER = "<tài khoản postgres local>"
$env:NUTRIMOM_PG_TEST_PASSWORD = "<mật khẩu local>"
.\mvnw.cmd clean verify
```

Không đặt `NUTRIMOM_PG_TEST_URL` thì `PregnancyWeeklyContentPostgresFallbackTest` tự bỏ qua; test chỉ bật
khi URL trỏ tới `localhost`/`127.0.0.1` và tự kiểm tra URL không chứa `supabase` trước khi chạy.

## Kết quả

`clean verify`: **467 test, 464 đạt, 1 lỗi, 2 skipped** → BUILD FAILURE do 1 test ngoài phạm vi:

| Test | Kết quả | Ghi chú |
| --- | --- | --- |
| `PregnancyWeeklyContentIntegrationTest` | 23/23 | 200 tuần 0/1/20/40/42, 409, 404, 422, 401, số đo, REVIEWED thiếu dữ liệu (5 trường hợp), dashboard |
| `PregnancyWeekContentPublicationTest` | 3/3 | Quy tắc publishable |
| `PregnancyWeeklyContentPostgresFallbackTest` | 1/1 | Lỗi SQL thật trên PostgreSQL → dashboard 200, `baby_summary = null`, block khác còn nguyên |
| `PregnancyContentDatasetValidatorTest` | 14/14 | Đủ 43, DRAFT, thiếu/trùng tuần, `20.5`, thiếu duyệt, placeholder, URL ví dụ, min/max, Unicode/nháy, version conflict, partial, JSON hỏng, mã thoát CLI |
| `PregnancyIntegrationTest`, `PregnancyProfileDeltaIntegrationTest` | 10/10, 5/5 | Cách tính tuổi thai không đổi |
| `MomDashboardIntegrationTest`, `MomDashboardCalendarIntegrationTest` | 3/3, 3/3 | |
| `NotificationIntegrationTest.listReturnsNewestFirstWithSpecFields` | **1 lỗi, không liên quan** | Chạy lại riêng 3 lần: 17/17 đạt cả 3. Test tạo 2 thông báo liên tiếp rồi sắp theo thời gian; trùng timestamp thì đảo thứ tự. Module notification không bị sửa trong nhánh này. Nên sửa ở task riêng |
| `PrivateR2LiveStorageTest`, `PayOSLiveTest` | skipped | Test dịch vụ thật, cần credentials, có sẵn từ trước |

## Bằng chứng cô lập transaction (mutation check)

Tạm bỏ `propagation = REQUIRES_NEW` trong `PregnancyWeekContentReader` rồi chạy lại test PostgreSQL: test **fail**
với `ERROR: current transaction is aborted, commands ignored until end of transaction block`. Khôi phục code →
test đạt. Vậy test thực sự phát hiện lỗi transaction mà H2 không tái hiện được.

## Validator trên dữ liệu thật của DB dùng chung (chỉ đọc)

Export 43 dòng `app.pregnancy_week_contents` → `PregnancyContentValidatorCli` → exit 1, **0 hợp lệ / 43 bị chặn**
(`content-validation-report.json`). Template rỗng `content-template.json` → exit 1 như mong đợi.

## Chưa kiểm chứng

- HTTP 200 với bài thật trên môi trường đích: chưa có bài đã duyệt.
- Migration xuất bản, chạy lại Flyway, nguyên tử khi có lỗi: chưa có migration (xem `deployment-and-recovery.md`).
- CI trên GitHub: chưa push.
- Field cân nặng qua dữ liệu live: chỉ kiểm qua test serialization (DB dùng chung không có số đo).
