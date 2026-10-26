# NutriMom Backend

Spring Boot, package `vn.nutrimom.<module>/{domain,dto,repository,service,web}`.
API prefix `/api/v1`. JSON **snake_case**, bọc `ApiResponse` (`data`, `meta`).
Lỗi theo `vn.nutrimom.common.exception.ErrorCode` — xem [docs/error-codes.md](docs/error-codes.md),
**không viết literal string**. `ErrorCatalogTest` khoá danh sách và HTTP status của enum đó.
Flyway ở `src/main/resources/db/migration`. Schema DB dùng prefix `app.`.

## Quy ước làm việc

- **Không tự commit, không tự push.** User tự làm. Không để tên Claude trong git.
- Nhánh đặt tên `feature/<kebab-case>` hoặc `fix/<kebab-case>`.

## Module dinh dưỡng mẹ bầu

Đang thi công, chia 10 nhánh backend. **Đừng đọc file đặc tả PDF** — mọi thứ cần thiết đã chép vào
`docs/nutrition/`:

| File | Khi nào |
|---|---|
| [docs/nutrition/README.md](docs/nutrition/README.md) | Không biết nhánh nào làm gì, thứ tự ra sao |
| [docs/nutrition/_CONTRACT.md](docs/nutrition/_CONTRACT.md) | **Mọi session đụng module này** |
| `docs/nutrition/branch-NN-*.md` | File của đúng nhánh đang làm |
| [docs/nutrition/DECISIONS.md](docs/nutrition/DECISIONS.md) | Khi file nhánh nhắc tới một QĐ-xx |

Không sửa migration đã chạy để thay đổi schema hoặc ép checksum khớp. Trước khi chọn số mới,
đối chiếu cả `main`, các nhánh đang làm và lịch sử Flyway của DB dùng chung.
V42–V56 thuộc Admin/consultation; V57 catalog, V58 hồ sơ dinh dưỡng.
DB dùng chung đã ghi V59 recommendation và V60 `meal_plans` (thực đơn); V61 = index hết hạn hội thoại trợ lý
(V32 trả về bản đã chạy); V62 = `reconcile_admin_migration_history` (trước đây đặt V60, đổi số vì trùng với meal_plans).
V63 = `shopping_lists` (danh sách mua sắm, nhánh 7) — số lấy từ `flyway_schema_history` lúc DB đang ở V62.
V64 = `diary_entries` + `meal_feedbacks` (nhật ký, nhánh 8) — DB vẫn ở V62 lúc đánh số, nhưng V63 đã
nằm trong repo nên số kế tiếp trống ở cả hai phía là 64.
V63 và V64 đã chạy lên DB dùng chung ngày 18/10/2026; V65 `community` đã chạy ngày 21/10/2026.
**V66 = `traffic analytics`, đã chạy lên DB dùng chung ngày 21/10/2026 nhưng KHÔNG có file nào trong
repo** — tác giả chưa push nhánh.
V67 = `payment_order_lifecycle` (hạn checkout + dấu vết đối soát, nhánh `feature/payment-order-lifecycle`)
— số lấy ngày 22/10/2026, đã đối chiếu cả `flyway_schema_history` (DB ở V66) lẫn mọi ref git (lớn
nhất V65). Đã chạy lên DB dùng chung ngày 22/10/2026.
V68 = `password_reset_tokens` (quên mật khẩu qua email, nhánh `feature/forgot-password-email`) — số lấy
ngày 25/10/2026, DB dùng chung ở V67, không ref git nào dùng V68+. Merge PR #67.
V69 = `web_reviews` (đánh giá web, nhánh `feature/web-reviews`, PR #68, merge sau V68).
V68 và V69 đã chạy lên DB dùng chung ngày 25/10/2026 (khởi động BE từ `main` 236336d, Flyway áp 68 rồi 69).
Số trống tiếp theo: **V70**.
Không tái sử dụng các số này. Những thay đổi tiếp theo phải dùng số còn trống sau khi đối chiếu.

**Đánh số bằng `flyway_schema_history`, không bằng file trong git.** V66 là ví dụ sống: ngày 22/10
một lượt `git log --all` vẫn báo số lớn nhất là V65, trong khi DB đã ở V66 từ hôm trước. Hậu quả của
việc chọn trùng số rất khó đọc — `out-of-order` đang tắt nên Flyway **im lặng bỏ qua** file đó
(`Schema "app" is up to date. No migration necessary.`), rồi app chết ở `ddl-auto: validate` với
thông báo thiếu cột, không hề nhắc gì tới migration. `validate-on-migrate` cũng tắt nên không có cả
cảnh báo checksum. `.env` của repo có sẵn credential; chạy câu này trong SQL Editor của Supabase
trước khi chọn số:

```sql
select version, description, installed_on
from app.flyway_schema_history order by installed_rank desc limit 10;
```
