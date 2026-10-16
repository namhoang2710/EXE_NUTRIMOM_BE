# Bàn giao BE — Rút gọn thai kỳ, đồng bộ migration, Notification

Ngày: 15/10/2026. Task: `PREGNANCY_SIMPLIFICATION_MIGRATION_NOTIFICATION_BE_TASK`.
Quyết định sản phẩm: **bỏ bài viết chăm sóc theo tuần**; giữ tính/lưu thai kỳ và hiển thị tuần, ngày, ngày dự sinh.

## 1. Trạng thái

| Phần | Trạng thái |
| --- | --- |
| A. Pregnancy (BE) | Xong trên nhánh, chưa push. Dashboard không còn query nội dung tuần; endpoint cũ giữ tạm (deprecated) |
| A. Pregnancy (FE) | Xong trên nhánh FE riêng, chưa push — xem `PREGNANCY_WEEKLY_CONTENT_REMOVAL_FE_HANDOFF.md` ở repo FE |
| B. Migration | Đã đối chiếu, **không cần migration mới**. Còn phụ thuộc: V42–V53 chưa có trong Git |
| C. Notification | Đã tái hiện nguyên nhân, sửa test, thêm case trùng thời điểm |
| Toàn luồng | **Chưa hoàn tất** cho tới khi FE được merge/deploy và V42–V53 được đưa lên Git; sau đó mới gỡ hẳn endpoint cũ |

## 2. Nhánh, commit, thứ tự tích hợp

- BE: `fix/pregnancy-weekly-content` (PR #37, draft), base `origin/main` `0b5bb84`.
  - `3ed85e8`, `c592ef4`: các bước trước (tách transaction dashboard, `weight_g_range`, chặn bài REVIEWED thiếu dữ liệu).
  - `0431a1e` refactor(pregnancy): bỏ query nội dung tuần khỏi dashboard, deprecate endpoint, gỡ validator/template publish.
  - `e3408c0` test(notification): cố định timestamp trong test thứ tự, thêm case trùng thời điểm.
  - Commit tài liệu này.
- FE: `fix/remove-weekly-pregnancy-content` từ `origin/dev` `d258cd6`, commit `e8d560b`.
- Thứ tự: (1) nhánh Admin đưa V42–V53 lên Git; (2) merge PR #37 + FE; (3) deploy FE; (4) PR sau gỡ endpoint, service,
  entity, DTO, `PregnancyWeekContentPublication`, test liên quan và `BabySummaryResponse`.

## 3. API / field

| Thành phần | Xử lý | Lý do |
| --- | --- | --- |
| `GET /dashboard/mom` → `baby_summary` | Luôn `null`, `@Deprecated` + Swagger `deprecated` | Giữ field cho client cũ đọc response; không còn query bảng nội dung |
| `GET /api/v1/pregnancy-content/weeks/{week}` | Giữ nguyên hành vi (200/404/409/422, review guard), đánh dấu deprecated | FE `dev` đang chạy vẫn gọi; gỡ sau khi FE mới deploy |
| `PregnancyWeekContentReader` | Đã xóa | Chỉ dashboard dùng |
| Validator/CLI/template/tracker/report publish | Đã xóa | Phục vụ kế hoạch publish đã bỏ |
| `ErrorCode.CONTENT_NOT_REVIEWED` | Giữ | Endpoint deprecated còn dùng; `docs/error-codes.md` dành cho mục 12 |
| `DashboardBlock.BABY_SUMMARY` | Đã xóa | Không còn dùng |
| Bảng `app.pregnancy_week_contents`, V5/V7/V22 | Giữ nguyên | Không sửa migration đã áp dụng; xóa bảng không phải điều kiện hoàn thành |

Giữ nguyên: công thức tính, các field `gestational_week`, `gestational_day`, `trimester`, `estimated_due_date`,
`days_until_due`, `calculation_source`, validation, quyền sở hữu, optimistic locking.

### Consumer đã kiểm tra

| Consumer | Kết quả |
| --- | --- |
| BE | Chỉ còn controller/service deprecated và test của nó dùng bảng nội dung |
| FE `origin/dev` `d258cd6` | Gọi weekly ở `HealthPage.tsx` (mở trang, sau lưu, retry). `baby_summary` chỉ có trong type, không render. Đã gỡ ở nhánh FE |
| Mobile `namhoang2710/Mobile_App` `master` `73ee131` (08/10) | Code `lib/` không gọi API (dữ liệu tĩnh); chỉ `docs/` dự kiến dùng endpoint và `baby_summary` |
| Mobile trong workspace khác (`APP_Nutrimom/Mobile_App`) | **Chưa xác minh** (không có trên máy này) → giữ tương thích tạm |

## 4. Migration

DB phát triển dùng chung (Supabase, schema `app`, nhóm xác nhận ngày 13/10 là “DB phát triển dùng chung”), đọc chỉ đọc ngày 15/10.
Production: chưa xác minh.

| Version | File | Trong Git (`origin/main` / mọi nhánh remote) | Trên DB dùng chung (installed_on, giờ DB) |
| --- | --- | --- | --- |
| V1–V41 | như `src/main/resources/db/migration` | Có | Có, V41 lúc 2026-10-12 04:56 |
| V42 | `admin_operations` | **Không** | 2026-10-12 12:05 |
| V43 | `consultation_schedule_snapshot` | **Không** | 2026-10-12 12:58 |
| V44 | `admin_customer_metrics` | **Không** | 2026-10-13 08:45 |
| V45 | `payment_source_legacy_writer_compatibility` | **Không** | 2026-10-13 12:13 |
| V46 | `recognize_subscription_upgrades` | **Không** | 2026-10-13 13:05 |
| V47 | `subscription_revenue_safe_order_codes` | **Không** | 2026-10-13 13:30 |
| V48 | `admin_notification_outbox` | **Không** | 2026-10-14 05:23 |
| V49 | `admin_recent_activity_indexes` | **Không** | 2026-10-15 03:15 |
| V50 | `admin_realtime` | **Không** | 2026-10-15 03:45 |
| V51 | `admin_expert_performance` | **Không** | 2026-10-15 06:43 |
| V52 | `expert_acceptance_timing` | **Không** | 2026-10-15 09:36 |
| V53 | `expert_kpi_v2_and_deadlines` | **Không** | 2026-10-15 12:06 |

- Tất cả `success = true`. Checksum đã ghi lại trong lần đọc (không cần cho task này vì không sửa file nào).
- V42–V53 không có trên máy này; theo task, nằm ở nhánh Admin trong workspace khác. **Cần người giữ nhánh đó push đúng
  bản đã chạy** (không viết lại SQL cùng version).
- Task này **không tạo migration**: bỏ query/tính năng không cần đổi schema. Nếu sau này cần migration mới thì phải từ
  **V54** trở lên (V53 đã dùng trên DB dùng chung).
- Không chạy migration, `repair`, `clean` hay ghi gì vào DB dùng chung.

### Kiểm thử migration

| Môi trường | Kết quả |
| --- | --- |
| PostgreSQL 18.4 riêng `nutrimom_weekly_test` (tạo mới) | Flyway chạy V1–V41 từ DB trống trong `clean verify`; test dashboard trên PostgreSQL đạt |
| PostgreSQL 18.4 riêng `nutrimom_e2e` (tạo mới) | BE khởi động: “Successfully applied 41 migrations… now at version v41”, health `UP`, dùng cho kiểm tra trình duyệt |
| Nâng cấp DB tương đương môi trường đích (tới V53) | **Chưa làm được**: thiếu file V42–V53 trong Git |

## 5. Notification

- Lỗi cũ: `NotificationIntegrationTest.listReturnsNewestFirstWithSpecFields` — expected `Thông báo mới`, actual `Thông báo cũ`.
- Nguyên nhân (đã tái hiện): `created_at` lấy giờ hệ thống, cắt về micro giây; hai thông báo tạo liền nhau có thể trùng
  (probe tạm: 3/2000 cặp trùng, 1 cặp đảo thứ tự). Truy vấn sắp `created_at desc, id desc` với `id` là UUID ngẫu nhiên →
  khi trùng thì thứ tự “mới nhất trước” không xác định. Phân trang vẫn ổn định vì `(created_at, id)` là duy nhất.
- Sửa: test gán `created_at` rõ ràng (không sleep) cho 2 test thứ tự; thêm `notificationsSharingATimestampArePagedWithoutGapsOrDuplicates`
  (5 thông báo cùng timestamp, `limit=2`, không lặp/mất). Không đổi code chạy thật, push, quiet hours, fan-out.
- Mutation check: đổi điều kiện cursor `id < :cursorId` thành `<=` → test mới fail; khôi phục → đạt.
- Chạy `NotificationIntegrationTest`, `NotificationProducerIntegrationTest`, `ActivityFeedSharingIntegrationTest` 3 lần liên tiếp: 18/6/7 đạt cả 3.

## 6. Kiểm thử

| Lần chạy | Lệnh | Kết quả |
| --- | --- | --- |
| Toàn bộ BE (cuối) | `.\mvnw.cmd clean verify` với `NUTRIMOM_PG_TEST_URL` trỏ PostgreSQL riêng | **453 test, 451 đạt, 0 fail, 0 error, 2 skipped**, BUILD SUCCESS |
| Skipped | `PrivateR2LiveStorageTest`, `PayOSLiveTest` | Test dịch vụ thật cần credentials, có sẵn từ trước |
| Pregnancy/dashboard | `PregnancyWeeklyContentIntegrationTest` 22, `PregnancyDashboardWithoutWeeklyContentPostgresTest` 1, `PregnancyIntegrationTest` 10, `PregnancyProfileDeltaIntegrationTest` 5, `MomDashboard*` 3+3 | Đạt |

Môi trường: Windows 11, OpenJDK 17.0.20.1, PostgreSQL 18.4 cục bộ. `git diff --check` sạch.

Test chứng minh dashboard không truy cập bảng nội dung: đổi tên bảng `pregnancy_week_contents` → endpoint cũ lỗi 5xx
(đối chứng), dashboard vẫn 200 với tuần/ngày đúng và `baby_summary = null` (H2 và PostgreSQL thật; dashboard không còn
try/catch cho bảng này nên nếu còn query sẽ lỗi).

## 7. Response thực tế (DB riêng, tài khoản demo cục bộ, sau khi cập nhật 21 tuần 0 ngày)

```json
// GET /api/v1/pregnancies/current → 200 (trích)
{ "gestational_week": 21, "gestational_day": 0, "estimated_due_date": "2027-02-25", "calculation_source": "MANUAL", "version": 1 }
// GET /api/v1/dashboard/mom → 200 (trích)
{ "pregnancy_summary": { "gestational_week": 21, "gestational_day": 0, "trimester": 2, "estimated_due_date": "2027-02-25", "days_until_due": 133 },
  "baby_summary": null }
// GET /api/v1/pregnancy-content/weeks/21 → 409 (endpoint deprecated, dữ liệu mẫu UNREVIEWED)
```

## 8. Xác nhận

- Không cần dataset tuần 0–42; không publish placeholder; không đổi placeholder sang `REVIEWED`.
- Không đổi công thức tính thai, không sửa/xóa migration đã áp dụng, không ghi vào DB dùng chung, không mất dữ liệu nghiệp vụ.
- Không commit secret `.env`, DB dump hay dữ liệu người dùng.
