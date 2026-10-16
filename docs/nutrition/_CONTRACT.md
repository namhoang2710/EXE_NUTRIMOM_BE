# Contract chung — mọi session làm module dinh dưỡng đọc file này trước

Phần này áp cho **tất cả** các nhánh. File nhánh chỉ mô tả phần riêng của nó.

---

## 1. Convention repo (đặc tả PDF KHÔNG nói, dễ làm sai nhất)

| Hạng mục | Repo dùng | Đặc tả viết |
|---|---|---|
| Đường dẫn | `/api/v1/...` | thiếu prefix — phải tự thêm |
| JSON | **snake_case** | camelCase trong ví dụ — **phải đổi** |
| Body thành công | bọc `ApiResponse` → `{ "data": ..., "meta": ... }` | trả thẳng object |
| Body lỗi | `{ "error": { code, message, fields, retryable, request_id } }` | bảng mã rời |
| Auth | JWT `Authorization: Bearer <token>` | "suy userId từ phiên đăng nhập" |
| Schema DB | `app.` | không nói |
| Package | `vn.nutrimom.nutrition.{domain,dto,repository,service,web}` | — |

Ví dụ: `previewId` trong đặc tả → `preview_id` trong JSON thật; `nutritionCoverage` → `nutrition_coverage`.
Tên field Java vẫn camelCase, chỉ lớp serialize đổi.

**Không tin `userId` trong body.** Mọi tài nguyên cá nhân suy chủ sở hữu từ JWT.

### Tên bảng đã chốt

`app.recipes` · `app.recipe_ingredients` · `app.ingredients` · `app.ingredient_components` ·
`app.ingredient_nutrients` · `app.allergens` · `app.nutrition_sources` · `app.price_estimates` ·
`app.meal_templates` · `app.eating_preferences` · `app.user_allergens` ·
`app.user_recipe_preferences` · `app.meal_plans` · `app.meal_slots` · `app.meal_plan_items` ·
`app.meal_plan_previews` · `app.diary_entries` · `app.meal_feedbacks` · `app.shopping_lists` ·
`app.shopping_list_items` · `app.recommendation_logs`

---

## 2. Flyway — KHÔNG đặt chỗ trước, hỏi database mỗi lần

**Đừng tin số migration cao nhất trong repo.** Ngày 16/10/2026, repo (cả `origin`) dừng ở **V41**
trong khi database Supabase dùng chung đã ở **V56**: V42–V56 là migration của người khác, áp thẳng
lên DB chung từ máy họ mà chưa push lên git. Nhánh 1 ban đầu đánh số V42, trùng với
"admin operations" đã chạy từ 12/10, và Flyway **im lặng bỏ qua** nó.

Vì sao im lặng: `validate-on-migrate: false` nên Flyway không than phiền về migration chưa áp dụng,
còn `out-of-order` mặc định `false` nên một version thấp hơn mức hiện tại của DB bị bỏ qua không
báo gì. Triệu chứng duy nhất nhìn thấy được là Hibernate `ddl-auto: validate` ném
`missing table [app.<tên bảng>]` lúc khởi động.

**Quy tắc cho mọi nhánh có migration:**

1. Ngay khi bắt đầu nhánh, hỏi database xem nó đang ở đâu:
   ```sql
   SELECT MAX(version::numeric) FROM app.flyway_schema_history;
   ```
2. Lấy số đó **+1**, không lấy theo số file trong repo.
3. Kiểm lại ngay trước khi merge — người khác có thể đã chiếm số trong lúc mình làm.

| Nhánh | Migration |
|---|---|
| 1 catalog | **V57** (DB đang ở V56 lúc đánh số) |
| 3 hồ sơ · 4 recommendation · 5 meal-plan · 7 shopping-list · 8 diary | lấy số lúc bắt đầu nhánh, theo quy tắc trên |
| 2 calculator · 6 plan-edit · 9 multiday · 10 revalidation | không có migration |

Chuyện DB chung chạy trước repo là vấn đề quy trình của nhóm, không phải thứ nhánh này sửa được —
nhưng mọi nhánh đều phải sống chung với nó.

---

## 3. ErrorCode — chỉ thêm đúng MỘT mã cho cả module

Nguồn sự thật: `vn.nutrimom.common.exception.ErrorCode`. Không viết literal string ở nơi phát lỗi.

**Đã có sẵn, dùng lại:** `VALIDATION_ERROR` (422) · `VERSION_CONFLICT` (409) ·
`IDEMPOTENCY_CONFLICT` (409) · `PLAN_UPGRADE_REQUIRED` (403) · `RATE_LIMITED` · mã auth và not-found.

**Thêm mới, ở nhánh 1, đúng một mã:**

```java
RECIPE_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT, false, "<thông điệp tiếng Việt>")
```

`ErrorCatalogTest` khoá 22 mã bắt buộc theo spec và HTTP status của chúng. **Thêm mã mới không làm
test đó đỏ** — nó chỉ đòi mọi mã trong enum phải có status và `defaultMessage` không rỗng. Đừng sửa
danh sách `MANDATORY` trong test khi thêm mã của module.

**`NO_MATCH` và `SEARCH_LIMIT` KHÔNG phải mã lỗi.** Chúng là `reason_codes` trong body trả về
**HTTP 200**, vì "không tìm được thực đơn" là kết quả nghiệp vụ hợp lệ, không phải lỗi hệ thống.

Chi tiết: [docs/error-codes.md](../error-codes.md).

---

## 4. Năm bất biến — vi phạm là sai nghiệp vụ, không phải sai style

1. **`null` khác `0`.** Thiếu dữ liệu vi chất thì cột để NULL, tổng mang `coverage = PARTIAL`, UI ghi
   "chưa đủ dữ liệu". Tuyệt đối không default 0, không tô đầy thanh tiến trình.
2. **Lọc trước, chấm điểm sau.** `EligibilityFilter` → `Ranker`, nối tiếp, không song song. Món chưa
   qua lọc không bao giờ được chấm điểm. Yêu thích và khóa món **không** bypass bước lọc.
3. **Dự kiến và đã ghi là hai tập dữ liệu độc lập.** `meal_plan_items` không bao giờ tự thành
   `diary_entries`. Sửa kế hoạch không viết lại nhật ký. Tổng "đã ghi" chỉ lấy từ nhật ký.
4. **Một `NutritionCalculator` duy nhất** cho tạo kế hoạch, đổi món và nhật ký. Không có nơi thứ hai
   tính tổng. Tiêu chí nghiệm thu: không chênh lệch giữa tổng API trả và tổng từ snapshot các mục.
5. **Không nới lỏng dị ứng để có kết quả.** Hết món thì trả `NO_MATCH` kèm lý do và mời sửa ưu tiên
   mềm (thời gian, ngân sách, món không thích). Không bao giờ bỏ điều kiện dị ứng hay chế độ ăn
   bắt buộc. Không chặn tính năng an toàn theo gói.

---

## 5. Versioning — bốn con số, đừng lẫn

| Trường | Tăng khi | Dùng để |
|---|---|---|
| `profile_version` | lưu hồ sơ ăn uống | kiểm tra kế hoạch còn hợp lệ |
| `plan_version` | mỗi thao tác sửa kế hoạch | optimistic lock, phát hiện 2 thiết bị |
| `recipe_version` | sửa công thức trong seed | snapshot lịch sử không bị viết lại |
| `rules_version` | sửa tập cấu hình seed | cache nhận biết, tái lập kết quả |

Nhật ký và mục kế hoạch **lưu snapshot** (tên, đơn vị, khẩu phần, giá trị đã dùng). Seed đổi thì
lịch sử đã ăn giữ nguyên.

---

## 6. Idempotency

`request_id` nằm **trong body** (không phải header `X-Request-Id` — cái đó là tracing/MDC, khác việc).
Unique cùng `user_id`. Gửi lại cùng `request_id` trả lại đúng bản ghi cũ, không sinh bản thứ hai.
Áp cho: tạo bản xem trước, tạo nhật ký.

---

## 7. Xong một nhánh là khi

- [ ] Migration chạy sạch trên DB trống **và** trên DB đã có dữ liệu
- [ ] `./mvnw test` xanh, gồm cả test mới của nhánh
- [ ] Mọi endpoint mới trả đúng envelope `ApiResponse`, JSON snake_case
- [ ] Mọi endpoint cá nhân chặn được tài khoản khác (kiểm tra chủ sở hữu)
- [ ] Các TC được liệt kê trong file nhánh có test tự động tương ứng
- [ ] Cập nhật `docs/NUTRITION_API.md` phần thuộc nhánh này
- [ ] **Không tự commit, không tự push.** User tự làm. Không để tên Claude trong git.
