# Bàn giao Pregnancy weekly content

## Trạng thái

- Kỹ thuật: **TECH_READY** trên nhánh, chưa merge — chi tiết `verification.md`.
- Dữ liệu: **CONTENT_PENDING** — DB phát triển dùng chung chỉ có 43 placeholder `UNREVIEWED`; chưa có bộ bài đã duyệt.
- Triển khai: **DEPLOY_PENDING** — PR #37 còn draft; chưa có migration xuất bản; chưa cập nhật DB nào.
- Phạm vi đã nghiệm thu: test tự động (H2 + PostgreSQL 18 riêng) và kiểm tra chỉ đọc DB phát triển dùng chung.
- Không ghi `DONE`: thiếu bộ bài đã duyệt, chưa merge, chưa có HTTP 200 trên môi trường đích.

## Source và PR

- Base: `origin/main` `0b5bb84`. Nhánh: `fix/pregnancy-weekly-content`. Commit bàn giao: commit tiếp theo `3ed85e8` trên nhánh này (chưa push).
- PR: [#37](https://github.com/namhoang2710/EXE_NUTRIMOM_BE/pull/37) — open, draft.
- Dùng lại từ `3ed85e8`: đọc nội dung tuần trong transaction `REQUIRES_NEW`, `weight_g_range` + alias `weight_grange`, test hợp đồng 200/409/404/422.
- File thay đổi lần này:

| File | Mục đích |
| --- | --- |
| `pregnancy/service/PregnancyWeekContentPublication.java` | Quy tắc “được phép hiển thị”: REVIEWED + người duyệt + thời điểm duyệt + version > 0 + đủ chữ |
| `pregnancy/service/PregnancyWeekContentService.java` | API dùng quy tắc trên; bản ghi REVIEWED thiếu dữ liệu → 409 `CONTENT_NOT_REVIEWED`, log tên field |
| `pregnancy/service/PregnancyWeekContentReader.java`, `dashboard/service/MomDashboardService.java` | Dashboard dùng cùng quy tắc (`findPublishableWeek`) |
| `pregnancy/content/PregnancyContentDatasetValidator.java`, `PregnancyContentValidatorCli.java` | Validator gói nội dung, chỉ đọc file |
| `src/test/.../PregnancyWeekContentPublicationTest.java`, `PregnancyWeeklyContentIntegrationTest.java` | Test quy tắc và API/dashboard với bản ghi REVIEWED thiếu dữ liệu |
| `src/test/.../PregnancyWeeklyContentPostgresFallbackTest.java` | Lỗi SQL thật trên PostgreSQL → dashboard vẫn 200 |
| `src/test/.../content/PregnancyContentDatasetValidatorTest.java` | Test validator |
| `docs/pregnancy-weekly-content/*`, `tools/pregnancy-content/README.md`, `docs/PREGNANCY_WEEKLY_CONTENT.md` | Tài liệu, mẫu, tracker, báo cáo |

Hợp đồng API không đổi: không thêm mã lỗi, không đổi field, giữ cách tính tuổi thai.

## Môi trường

- DB phát triển dùng chung: Supabase (định danh che), schema `app`, PostgreSQL 17.6.
- Flyway: **V51** trên DB dùng chung; `origin/main` chỉ tới **V41**. V42–V51 không có trên nhánh remote nào.
- Lần này **không ghi** gì vào DB dùng chung; không chạy migration.
- Production: chưa xác minh.

## Nội dung

- Nguồn dataset: chưa có. Export DB hiện tại: 43 placeholder, `sha256` file export trong `content-validation-report.json`.
- Tuần đã duyệt và xuất bản: không có. Tuần còn thiếu nội dung thật: 0–42.
- Người/đơn vị duyệt: chưa được nhóm chỉ định.
- Validator trên export DB: 0 hợp lệ / 43 bị chặn (`PLACEHOLDER_TEXT`, `UNAPPROVED_CONTENT`).

## Thay đổi dữ liệu

- Migration/import: chưa có (đúng quy tắc: không đưa placeholder vào Flyway).
- Thiết kế migration, preflight, backup và phục hồi: `deployment-and-recovery.md`.

## Kiểm thử

Xem `verification.md`.

## HTTP sau triển khai

Chưa triển khai. Kết quả trước cập nhật (13/10, DB phát triển dùng chung): weekly tuần 20 → 409
`CONTENT_NOT_REVIEWED`; dashboard mẹ → 200, `baby_summary = null`. Đây là fallback đúng, **chưa** đạt tiêu chí có bài.

## Công việc còn lại

| Việc | Người phụ trách | Đầu vào cần nhận | Tiêu chí hoàn tất |
| --- | --- | --- | --- |
| Chỉ định người biên soạn và người duyệt chuyên môn | Nhóm trưởng / PO | Tên người/đơn vị, phạm vi tuần | Có tên trong `content-review-tracker.csv` |
| Cung cấp bộ bài tuần 0–42 | Người biên soạn | Điền `content-template.json` (hoặc Word/Sheet để BE chuyển đổi) | Validator không còn lỗi nội dung |
| Duyệt và ghi xác nhận | Người duyệt | `reviewed_by`, `reviewed_at`, `approval_reference` cho đúng `sha256` gói | `dataset_status = APPROVED`, validator exit 0 |
| Đẩy V42–V51 lên Git | Người giữ nhánh Admin (`feat/admin-operations-dashboard`) | File SQL đúng bản đã chạy | `main` khớp `flyway_schema_history` của DB dùng chung |
| Review/merge PR #37 | Nhóm BE | Review code | CI xanh, merge vào `main` |
| Tạo migration xuất bản (version > V51 tại thời điểm làm) | BE | Gói APPROVED + V42–V51 trên `main` | Test trên PostgreSQL riêng, Flyway chạy lại không đổi gì |
| Triển khai và kiểm HTTP | BE + nhóm trưởng | Quyền môi trường đích, tài khoản QA | Weekly 200 cho mọi tuần đã xuất bản; dashboard `baby_summary.week` = tuần thai |
| Đối chiếu “máy bạn hiển thị đủ” (nếu vẫn còn) | Bạn cùng nhóm | URL BE, `git rev-parse HEAD`, response `/pregnancy-content/weeks/20` | Biết rõ nguồn khác nhau |
