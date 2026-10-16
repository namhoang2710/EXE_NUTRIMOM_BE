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

Flyway đã **đặt chỗ sẵn** theo thứ tự nhánh: V42 catalog · V43 hồ sơ · V44 recommendation ·
V45 meal-plan · V46 shopping-list · V47 diary. Lấy đúng số của nhánh mình, merge đúng thứ tự.
