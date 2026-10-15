# Điều tra hiện trạng — Pregnancy weekly content

Thời điểm kiểm tra: 15/10/2026 (giờ Việt Nam). Mọi truy vấn DB đều trong transaction `READ ONLY`.

## 1. Source và nhánh

| Mục | Kết quả |
| --- | --- |
| Remote | `github.com/namhoang2710/EXE_NUTRIMOM_BE`, nhánh tích hợp `main` |
| `origin/main` | `0b5bb84` (13/10, merge PR #36), migration cao nhất **V41** |
| Nhánh làm việc | `fix/pregnancy-weekly-content`, base = `origin/main` (không bị chậm), đã có commit `3ed85e8` |
| PR #37 | **Open, draft, chưa merge**, head `3ed85e8` (đọc qua GitHub API) |
| Checkout dùng | `C:\EXE201\EXE_NUTRIMOM_BE`, working tree sạch trước khi làm; không lẫn code Admin |
| V42–V51 | Không có trên **bất kỳ nhánh remote nào**. Task ghi máy khác đang ở `feat/admin-operations-dashboard` → các migration này chỉ nằm ở nhánh Admin chưa push |

Tái sử dụng từ `3ed85e8`: `PregnancyWeekContentReader` (`REQUIRES_NEW`), field `weight_g_range` + alias
`weight_grange`, bộ test `PregnancyWeeklyContentIntegrationTest`.

Bổ sung trong lần này: chặn bản ghi `REVIEWED` thiếu dữ liệu (`PregnancyWeekContentPublication`), test
fallback trên PostgreSQL thật, validator + template + tracker + tài liệu bàn giao.

## 2. Môi trường

| Trường | DB phát triển dùng chung | PostgreSQL test riêng |
| --- | --- | --- |
| Loại | Development dùng chung (nhóm xác nhận 13/10) | DB tạm trên máy, chỉ cho test |
| DB | Supabase project của nhóm (định danh che), DB `postgres`, schema `app`, PostgreSQL 17.6 | `localhost:5432/nutrimom_weekly_test`, PostgreSQL 18.4 |
| Ứng dụng gọi | BE local khi chạy với `.env` hiện tại; bản deploy chưa xác minh | Chỉ test tự động |
| Migration cao nhất | **V51** (`admin expert performance`, 15/10). V46–V47: 13/10, V48: 14/10, V49–V51: 15/10 | V41 (Flyway chạy từ đầu theo source) |
| Người phụ trách | Nhóm trưởng (chưa ghi tên) — **CHƯA XÁC MINH** | Người chạy test |

Production: **CHƯA XÁC MINH** — không có thông tin hay quyền truy cập.

## 3. Dữ liệu `app.pregnancy_week_contents` trên DB phát triển dùng chung

- Cấu trúc đúng V5 + V22 (21 cột, 8 cột nội dung NOT NULL; metadata duyệt nullable; `content_version` NOT NULL).
- `UNREVIEWED`: **43 dòng**, tuần 0–42; không thiếu tuần; không có dòng `REVIEWED`.
- `reviewed_by` / `reviewed_at`: 0/43 có giá trị. `content_version` = 1 cho cả 43.
- 43/43 dòng chứa câu mẫu “Nội dung tổng quan cho tuần N đang chờ đội ngũ chuyên môn y khoa của NutriMom thẩm định.”
- Không có số đo thai nhi. Nguồn duy nhất là trang tổng quan WHO “Pregnancy”.
- Cập nhật lần cuối: 29/09/2026 (khi chuyển sang PostgreSQL).
- Không có dòng `REVIEWED` thiếu metadata (tình huống G không xảy ra).

Export 43 dòng sang định dạng template và chạy validator: **0 tuần hợp lệ, 43 bị chặn**, lỗi
`PLACEHOLDER_TEXT` (summary/baby_development/mother_changes) và `UNAPPROVED_CONTENT`
(reviewed_by/reviewed_at/approval_reference). Báo cáo: `content-validation-report.json`.

## 4. Tìm nguồn bộ bài đã duyệt

| Nơi đã tìm | Kết quả |
| --- | --- |
| `src/main/resources/db/migration` (V5, V7, V22) | Chỉ có bảng + 43 placeholder |
| `docs/`, resources, test fixtures | Chỉ có fixture tổng hợp cho test |
| Lịch sử Git mọi nhánh remote | Không có migration/seed nội dung đã duyệt |
| Bảng DB phát triển dùng chung | Chỉ placeholder (mục 3) |
| Báo cáo “máy bạn hiển thị đủ” (13/10) | Chưa nhận được URL/response để đối chiếu — tình huống H **chưa xác minh** |

Kết luận: **tình huống D** (chỉ có placeholder) + **K** (PR #37 chưa merge) + **L** (DB dùng chung đi trước
source: V42–V51 chưa có trên `main`). Bộ bài phải do nhóm cung cấp và duyệt (xem `handoff.md`).
