# Nhánh 1 — Catalog món và seed

> Đọc trước: [_CONTRACT.md](_CONTRACT.md). Liên quan: [DECISIONS.md](DECISIONS.md) QĐ-05, QĐ-06.

**Mục tiêu:** có bộ dữ liệu món chuẩn hóa trong DB và hai endpoint tra cứu công khai. Mọi nhánh sau
đều dựng trên nhánh này.

| | |
|---|---|
| Nhánh git | `feature/nutrition-catalog-seed` |
| Cắt từ | `main` |
| Migration | **V57** — xem `_CONTRACT.md` mục 2 trước khi tin con số này |
| Demo được | Mở web chưa đăng nhập, xem danh sách món, lọc theo bữa và thời gian nấu |

---

## 1. Chín bảng catalog (V57)

| Bảng | Cột tối thiểu |
|---|---|
| `app.recipes` | `id`, `name`, `version`, `meal_types`, `role`, `servings_yield`, `serving_gram`, `minutes`, `steps`, `safety_notes`, `image_key`, `main_ingredient_tag` |
| `app.recipe_ingredients` | `recipe_id`, `ingredient_id`, `quantity_gram`, `preparation_state` |
| `app.ingredients` | `id`, `canonical_name`, `food_state`, `food_group`, `allergen_status`, `source_id` |
| `app.ingredient_components` | `ingredient_id`, `child_ingredient_id` |
| `app.ingredient_nutrients` | `ingredient_id`, `nutrient_code`, `amount_per_100g`, `unit`, `status`, `source_id` |
| `app.allergens` | `allergen_id`, `name` |
| `app.nutrition_sources` | `source_id`, `name`, `record_ref` |
| `app.price_estimates` | `ingredient_id`, `price_per_base_unit_vnd`, `base_unit`, `estimated_at` |
| `app.meal_templates` | `meal_type`, `mode`, `required_roles`, `role_target_share`, `default_serving_options` |

Dị ứng của nguyên liệu nối qua bảng nối `ingredient_id` ↔ `allergen_id`.

### Ràng buộc bắt buộc

- `ingredient_nutrients.amount_per_100g` **CHO PHÉP NULL**. Không default 0, không NOT NULL.
  (bất biến 1 trong `_CONTRACT.md`)
- `ingredients.allergen_status`: `KNOWN` | `UNKNOWN`. Mảng allergen rỗng **chỉ có nghĩa** "không ghi
  nhận thành phần gây dị ứng" **sau khi** đã ở trạng thái `KNOWN`. `UNKNOWN` là chưa biết, khác hẳn.
- `ingredient_components` **không được tạo vòng** — phát hiện vòng lúc nhập seed, không phải lúc chạy.
- `recipes.image_key VARCHAR(255) NULL` — lưu **object key**, không lưu URL đầy đủ (QĐ-05).
- `servings_yield > 0`; `quantity_gram >= 0`; giá và thời gian không âm.
- Index: `recipes` theo loại bữa và vai trò.
- **BR18:** giá là ước tính VND, phải lưu `estimated_at` và nguồn. Không phải báo giá cửa hàng.

### Tên địa phương

Nhiều cách viết của cùng một nguyên liệu phải ánh xạ về **một** `ingredient_id` chuẩn, nếu không lọc
dị ứng sẽ sót. Làm bằng bảng alias hoặc cột mảng tên gọi — tùy chọn, nhưng phải có.

---

## 2. Seed

Bốn file JSON trong `src/main/resources/`: `recipes.json`, `ingredients.json`,
`nutrientSources.json`, `nutritionRules.json`.

**Nhánh này chỉ cần 8–10 món** đủ phủ các loại bữa (sáng / trưa / tối / phụ) và đủ vai trò
(món hoàn chỉnh, tinh bột, đạm, rau). Bộ đầy đủ 40–60 món là việc nhập liệu chạy song song.

### Script kiểm tra seed — chạy TRƯỚC khi nhập DB

Kiểm đủ sáu thứ, fail là chặn nhập:

1. Đơn vị dinh dưỡng thuộc danh mục `Nutrient`, không tự chế trong từng món
2. Mọi mã tham chiếu (`ingredient_id`, `source_id`, `allergen_id`) tồn tại
3. `servings_yield > 0` và `serving_gram` có khi món có số phần theo gram
4. Thành phần phụ: gia vị và nước dùng hỗn hợp phải khai `ingredient_components`
5. **Không có vòng** trong `ingredient_components`
6. Giá trị thiếu để NULL, không phải 0

Không xây trang CRUD cho dữ liệu seed. Sửa seed thì tăng `rules_version` / `recipe_version`.

---

## 3. Endpoint

```
GET /api/v1/recipes          phân trang; lọc meal_type, role, max_minutes
GET /api/v1/recipes/{id}     công thức, khẩu phần, thành phần, dữ liệu ước tính
```

JSON snake_case, bọc `ApiResponse`. DTO dựng `image_url` từ `image_key` lúc trả về — không lưu URL
trong DB.

### Security (QĐ-06)

Thêm **đúng một dòng** vào `vn/nutrimom/config/SecurityConfig.java`, theo mẫu
`/api/v1/knowledge/articles` và `/api/v1/experts` đang dùng:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/recipes", "/api/v1/recipes/*").permitAll()
```

Không permitAll cả prefix. Mọi path khác của module giữ `authenticated`.

### Rate limit

Endpoint công khai nên phải có. Thêm policy `recipes` vào `policies:` trong `application.yml`, khóa
theo IP. `RateLimitFilter` khớp path bằng `AntPathMatcher` theo tên policy — **không có annotation**.

---

## 4. ErrorCode — thêm đúng một mã cho cả module

```java
RECIPE_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT, false, "<thông điệp tiếng Việt>")
```

Nhánh này chưa dùng tới nó (nhánh 6 mới dùng), nhưng khai ở đây để các nhánh sau không phải sửa enum
→ tránh merge conflict như lần `fix/global-exception-handler-merge`.

`ErrorCatalogTest` khoá 22 mã bắt buộc theo spec; **thêm mã mới không làm nó đỏ**, chỉ cần mã có
status và `defaultMessage` không rỗng. Đừng đụng vào danh sách `MANDATORY` trong test đó.

Không thêm mã nào khác. Xem `_CONTRACT.md` mục 3.

---

## 5. Ảnh món (QĐ-05)

Dùng đường **public media** sẵn có: `knowledge/service/R2StorageService`, bucket
`app.knowledge.r2.bucket-name` — cùng chỗ ảnh bài viết và avatar chuyên gia. Không dùng
`PrivateR2StorageService` (đó là hồ sơ y tế).

Prefix `recipes/`. **Không presigned URL** — endpoint công khai, presigned hết hạn sẽ phá cache ảnh.
**Không có endpoint upload** — không có màn quản trị; ảnh đẩy lên bằng script seed.

---

## KHÔNG làm trong nhánh này

NutritionCalculator · hồ sơ ăn uống · lọc/xếp hạng · lập thực đơn · mua sắm · nhật ký ·
trang CRUD seed · endpoint upload ảnh · thêm `PlanFeature` mới (QĐ-04).

---

## Xong là khi

- [ ] V57 chạy sạch trên DB trống và DB đã có dữ liệu
- [ ] Script kiểm tra seed phát hiện được: vòng trong component, mã tham chiếu sai, đơn vị lạ
- [ ] Hai endpoint trả đúng dữ liệu **khi chưa đăng nhập**
- [ ] Lọc theo `meal_type` / `role` / `max_minutes` chạy đúng, có phân trang
- [ ] `ErrorCatalogTest` xanh sau khi thêm mã mới
- [ ] Tạo `docs/NUTRITION_API.md`, viết phần catalog
- [ ] Không tự commit, không tự push
