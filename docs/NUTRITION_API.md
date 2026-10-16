# Nutrition API (dinh dưỡng mẹ bầu)

Module dinh dưỡng được thi công theo 10 nhánh; tài liệu thi công ở
[docs/nutrition/](nutrition/README.md). File này mô tả **những gì đã có trên API**, cập nhật theo
từng nhánh merge.

| Nhánh | Trạng thái |
|---|---|
| 1 — catalog và seed (V57) | **đã làm** |
| 2 — NutritionCalculator (không có endpoint) | **đã làm** |
| 3–10 | chưa |

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

## NutritionCalculator (nhánh 2)

Nhánh này **không thêm endpoint nào** — nó là bộ tính dinh dưỡng mà nhánh 5 (lập kế hoạch), nhánh 6
(đổi món), nhánh 7 (mua sắm) và nhánh 8 (nhật ký) gọi lại. Ghi ở đây vì hình dạng khối dinh dưỡng
trong mọi response sau này do nó quyết định.

**Không nơi nào khác được tự cộng tổng dinh dưỡng.** Có nơi thứ hai là sớm muộn tổng API trả về sẽ
lệch với tổng tính từ snapshot của từng mục, và không ai biết bên nào đúng.

### API nội bộ

| Lớp | Việc |
|---|---|
| `NutritionCalculator` | tổng của một món, một mục, hay một tập mục; quy phần ra lượng nguyên liệu |
| `EnergyEstimator` | ước tính EER, **tùy chọn** |
| `NutritionRulesProvider` | nạp bảng vi chất, hệ số EER và hệ số giữ lại từ `nutritionRules.json` |
| `NutritionSnapshots` | ảnh chụp công thức và nguyên liệu — đầu vào của hai lớp trên |

Calculator nhận **snapshot**, không nhận entity và không truy vấn database: người gọi nạp nguyên
liệu một lượt (`findAllById`) rồi dựng snapshot bằng `NutritionSnapshots.snapshotOf`. Nhật ký và
mục kế hoạch lưu lại chính snapshot đó kèm `recipe_version`, nên seed sửa công thức về sau không
viết lại bữa đã ăn (BR17).

```
lượng chất k của công thức = Σ ( q_i / 100 × a_i,k × r_i,k )
lượng chất k một phần      = lượng của công thức / servings_yield
lượng chất k của mục       = lượng một phần × servings_selected
```

Chia trước, nhân số phần sau — nhờ vậy 1,5 phần bằng **đúng** 1,5 lần 1 phần, cả dinh dưỡng lẫn
lượng nguyên liệu (TC08). Kết quả **chưa làm tròn**; làm tròn là việc của lớp hiển thị.

### Hình dạng khối dinh dưỡng trong response các nhánh sau

```json
{
  "nutrition": {
    "ENERGY":  { "known_value": 612.5, "unit": "kcal",     "coverage": "COMPLETE" },
    "FOLATE":  { "known_value": 8.0,   "unit": "mcg_dfe",  "coverage": "PARTIAL",
                 "missing_ingredient_ids": ["ing.nuoc-mam"] },
    "IODINE":  { "known_value": null,  "unit": "mcg",      "coverage": "PARTIAL",
                 "missing_ingredient_ids": ["ing.gao-te", "ing.nuoc-mam"] }
  },
  "method_notes": []
}
```

| Trường | Nghĩa |
|---|---|
| `known_value` | tổng của **phần đã biết**; `null` = chưa nguyên liệu nào có số liệu |
| `coverage` | `COMPLETE` \| `PARTIAL` — PARTIAL ngay khi có một nguyên liệu thiếu |
| `missing_ingredient_ids` | nguyên liệu chưa có số liệu cho chất đó |
| `method_notes` | giả định đã dùng, ví dụ hệ số giữ lại `r = 1` khi chưa có hệ số phù hợp |

- **`null` không phải 0.** Nguyên liệu không khai một chất, hoặc khai `status = MISSING`, đều là
  chưa có dữ liệu. Giao diện ghi "chưa đủ dữ liệu cho chất này", không tô đầy thanh tiến trình.
- Chất **không nguyên liệu nào khai** vẫn xuất hiện trong kết quả với `known_value: null` — bỏ nó
  đi thì giao diện không có gì để nói là đang thiếu.
- `coverage` là thuộc tính của **dữ liệu**, không phải trạng thái kế hoạch: thực đơn `READY` vẫn có
  thể `PARTIAL`.

### Trạng thái thực phẩm

Bản ghi dinh dưỡng đã mô tả đúng trạng thái đang dùng → `r = 1`, **không áp hao hụt lần nữa**. Bản
ghi mô tả nguyên liệu sống mà món dùng ở trạng thái khác → tra `retentionFactors`; chưa có hệ số
phù hợp thì vẫn `r = 1` nhưng ghi vào `method_notes` rằng đó là **giả định ước tính**, không phải
số đo sau nấu. Bảng `retentionFactors` trong seed hiện **cố ý rỗng**.

Quy gram món đã nấu ra số phần dùng `serving_gram` (khối lượng **chín** một phần). Món chưa cân
được thì không quy đổi — hỏi người dùng theo số phần, không bao giờ lấy gram nguyên liệu sống thay
vào. Không có quy đổi ml sang gram nào.

### Bảng vi chất tham chiếu

Nạp từ `nutritionRules.json`, nhóm tuổi 19–50: folate 600 `mcg_dfe` · sắt 27 mg · canxi 1.000 mg ·
choline 450 mg (AI) · iốt 220 `mcg` · vitamin D 15 `mcg`.

Đây **không** phải mức phân bổ cho một bữa, cũng không phải liều cần bổ sung. **DFE khác lượng axit
folic ghi trên nhãn.** MVP chỉ ghi lượng từ thực phẩm, chưa nhập thuốc và thực phẩm bổ sung, nên
không kết luận tổng nạp vào.

### Ước tính năng lượng EER — tùy chọn

Chỉ tính khi có đủ tuổi (19–50), chiều cao, cân nặng hiện tại, tuổi thai, nhóm vận động — và thêm
cân nặng trước thai kỳ từ tam cá nguyệt 2 (thành phần `D` chọn theo BMI trước thai kỳ). Ranh giới
hai bộ hệ số là **14 tuần tròn**: 13 tuần 6 ngày vẫn dùng bộ tam cá nguyệt 1.

Thiếu dữ liệu hoặc giá trị vô lý → **không tính**, trả danh sách `unmet` theo tên trường
(`height_cm`, `current_weight_kg`, …) kèm lý do `MISSING` | `OUT_OF_RANGE` | `NOT_SUPPORTED`. Lúc
đó **không hiển thị phần trăm hoàn thành năng lượng** và không mặc định 2.000 kcal (BR20).

- Công thức **đã gồm** yếu tố thai kỳ → không cộng thêm 340 hay 450 kcal.
- `D` là thành phần toán học của mô hình, **không phải** thiết lập giảm cân.
- Không suy nhóm vận động từ số bước chân. EER là ước tính có sai số, không phải chỉ định khẩu phần.

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
- Nhánh 2 thêm `retentionFactors` và `energyEstimate` vào `nutritionRules.json`, nên
  `rules_version` đã tăng lên `nutrition-seed-v2`. Hai khối này **không vào database**:
  `NutritionRulesProvider` đọc thẳng từ classpath, và thiếu hệ số thì ứng dụng không khởi động.

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
