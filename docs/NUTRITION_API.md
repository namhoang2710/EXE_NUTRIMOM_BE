# Nutrition API (dinh dưỡng mẹ bầu)

Module dinh dưỡng được thi công theo 10 nhánh; tài liệu thi công ở
[docs/nutrition/](nutrition/README.md). File này mô tả **những gì đã có trên API**, cập nhật theo
từng nhánh merge.

| Nhánh | Trạng thái |
|---|---|
| 1 — catalog và seed (V57) | **đã làm** |
| 2–10 | chưa |

JSON snake_case, bọc `ApiResponse` (`data`, `meta`); lỗi theo [error-codes.md](error-codes.md).

---

## Catalog món (nhánh 1)

Hai endpoint này **công khai**, xem được khi chưa đăng nhập — danh mục món là nội dung giới thiệu.
Mọi endpoint cá nhân của module (hồ sơ, gợi ý, thực đơn, nhật ký) ở các nhánh sau đều cần JWT.

Rate limit: policy `recipes`, 60 lượt/phút, khoá theo IP khi chưa đăng nhập.

### `GET /api/v1/recipes`

Danh sách công thức, **phân trang bằng con trỏ** (không phải offset: danh mục được nạp lại mỗi lần
seed đổi phiên bản, offset có thể nhảy dòng giữa hai lượt gọi).

| Tham số | Kiểu | Ý nghĩa |
|---|---|---|
| `mealType` | `BREAKFAST` \| `LUNCH` \| `DINNER` \| `SNACK` | bỏ trống = mọi loại |
| `role` | `MAIN_DISH` \| `STAPLE` \| `PROTEIN` \| `VEGETABLE` \| `SIDE` \| `SNACK` | vai trò trong bữa |
| `maxMinutes` | 1–600 | chỉ lấy món nấu trong tối đa chừng này phút |
| `cursor` | string | `next_cursor` của lượt gọi trước |
| `limit` | 1–50 | mặc định 20 |

```json
{
  "data": {
    "items": [
      {
        "id": "rec.com-trang",
        "name": "Cơm trắng",
        "version": 1,
        "role": "STAPLE",
        "meal_types": ["DINNER", "LUNCH"],
        "minutes": 25,
        "servings_yield": 4.00,
        "serving_gram": 180.00,
        "main_ingredient_tag": "ing.gao-te"
      }
    ],
    "next_cursor": "rec.com-trang",
    "has_more": true
  },
  "meta": { "request_id": "...", "server_time": "2026-10-16T04:00:00Z" }
}
```

`image_url` chỉ xuất hiện khi món có ảnh **và** đã cấu hình `app.knowledge.r2.public-base-url`.
Database lưu object key, URL được dựng lúc trả về.

### `GET /api/v1/recipes/{recipeId}`

Chi tiết một món. Không tìm thấy → `404 RESOURCE_NOT_FOUND`.

```json
{
  "data": {
    "summary": { "...": "như trên" },
    "steps": "1. Vo sạch 300g gạo tẻ.\n2. ...",
    "safety_notes": "Kho tới khi thịt chín hoàn toàn...",
    "ingredients": [
      {
        "ingredient_id": "ing.gao-te",
        "canonical_name": "Gạo tẻ",
        "quantity_gram": 300.00,
        "preparation_state": "RAW",
        "food_group": "GRAIN",
        "allergen_status": "KNOWN",
        "allergen_ids": [],
        "price": {
          "price_per_base_unit_vnd": 22000.00,
          "base_unit": "kg",
          "estimated_at": "2026-10-14"
        }
      }
    ],
    "data_state": {
      "nutrient_codes_missing_data": ["FOLATE"],
      "ingredients_with_unknown_allergen": [],
      "price_known_for_all_ingredients": true
    }
  }
}
```

#### `data_state` — trạng thái dữ liệu, không phải tổng dinh dưỡng

| Trường | Giao diện phải làm gì |
|---|---|
| `nutrient_codes_missing_data` | Ghi "chưa đủ dữ liệu cho chất này". **Không** tô đầy thanh, **không** hiện 0 |
| `ingredients_with_unknown_allergen` | Giải thích vì sao món bị loại khỏi thực đơn cá nhân hoá (BR03), thay vì chỉ ẩn nó |
| `price_known_for_all_ingredients` | `false` → tổng chi phí ở các nhánh sau mang nhãn chưa đầy đủ |

**Cố ý không có tổng dinh dưỡng và không có tổng chi phí của món.** Tổng dinh dưỡng thuộc
`NutritionCalculator` ở nhánh 2. Tổng chi phí đòi quy đổi đơn vị (kg, lít, quả) sang lượng gram của
công thức, mà quy tắc quy đổi chưa được định nghĩa — đoán một mật độ chung chính là lỗi đặc tả cấm.

---

## Seed

Danh mục nạp từ `src/main/resources/nutrition/` lúc khởi động: `nutrientSources.json`,
`nutritionRules.json`, `ingredients.json`, `recipes.json`.

- `NutritionSeedValidator` chạy **trước khi** chạm vào database. Seed sai thì ứng dụng
  **không khởi động**, kèm danh sách đầy đủ vi phạm.
- Nạp lại chỉ khi `rules_version` trong `nutritionRules.json` khác giá trị ở
  `app.nutrition_seed_state`. Sửa seed thì phải tăng số này.
- **Cập nhật chứ không xoá sạch**: dòng biến mất khỏi seed chỉ bị ghi log cảnh báo. Từ nhánh 5 trở
  đi kế hoạch và nhật ký tham chiếu tới `recipes`; xoá đi là viết lại lịch sử đã ăn (BR17).
- Tắt bằng `app.nutrition.seed-enabled=false` (profile test đặt sẵn `false`).

> Số liệu dinh dưỡng và giá trong seed hiện mang nguồn `src.demo-placeholder` — giá trị **tạm** để
> chạy được luồng, **chưa đối chiếu nguồn chính thức**. Chuẩn hoá 40–60 món có dẫn nguồn là công
> việc nhập liệu riêng.

---

## Error code

Module thêm **đúng một** mã vào danh mục chung:

| Mã | HTTP | Khi nào |
|---|---|---|
| `RECIPE_NOT_ELIGIBLE` | 422 | Món vi phạm thành phần hoặc giới hạn bắt buộc của hồ sơ (dùng từ nhánh 6) |

Mọi tình huống khác dùng lại mã sẵn có: `VALIDATION_ERROR`, `VERSION_CONFLICT`,
`IDEMPOTENCY_CONFLICT`, `RESOURCE_NOT_FOUND`, `PLAN_UPGRADE_REQUIRED`.

`NO_MATCH` và `SEARCH_LIMIT` **không phải** mã lỗi — chúng là `reason_codes` trong body trả
**HTTP 200**, vì không tìm được thực đơn là kết quả nghiệp vụ hợp lệ.
