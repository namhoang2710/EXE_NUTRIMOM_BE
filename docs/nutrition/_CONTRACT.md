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

## 2. Flyway — số version đặt chỗ sẵn

Repo đang ở **V41**. Mỗi nhánh lấy đúng số của mình, **merge theo đúng thứ tự nhánh**:

| V42 | V43 | V44 | V45 | V46 | V47 |
|---|---|---|---|---|---|
| catalog | hồ sơ | recommendation | meal-plan | shopping-list | diary |

Merge lệch thứ tự thì nhánh sau phải đổi tên file migration. Nhánh 2, 6, 9, 10 không có migration.

---

## 3. ErrorCode — chỉ thêm đúng MỘT mã cho cả module

Nguồn sự thật: `vn.nutrimom.common.exception.ErrorCode`. Không viết literal string ở nơi phát lỗi.

**Đã có sẵn, dùng lại:** `VALIDATION_ERROR` (422) · `VERSION_CONFLICT` (409) ·
`IDEMPOTENCY_CONFLICT` (409) · `PLAN_UPGRADE_REQUIRED` (403) · `RATE_LIMITED` · mã auth và not-found.

**Thêm mới, ở nhánh 1, đúng một mã:**

```java
RECIPE_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT, false, "<thông điệp tiếng Việt>")
```

`ErrorCatalogTest` khoá danh sách + HTTP status của enum này — **thêm mã thì phải cập nhật test đó**,
nếu không build đỏ.

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
