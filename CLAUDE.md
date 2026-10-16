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
DB dùng chung đã ghi V59 recommendation; nhánh sửa lịch sử Admin dùng V60.
Không tái sử dụng các số này. Những thay đổi tiếp theo phải dùng số còn trống sau khi đối chiếu.
