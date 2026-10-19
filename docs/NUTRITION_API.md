# Nutrition API (dinh dưỡng mẹ bầu)

Module dinh dưỡng được thi công theo 10 nhánh; tài liệu thi công ở
[docs/nutrition/](nutrition/README.md). File này mô tả **những gì đã có trên API**, cập nhật theo
từng nhánh merge.

| Nhánh | Trạng thái |
|---|---|
| 1 — catalog và seed (V57) | **đã làm** |
| 2 — NutritionCalculator (không có endpoint) | **đã làm** |
| 3 — hồ sơ ăn uống (V58) | **đã làm** |
| 4 — lọc điều kiện và xếp hạng (V59) | **đã làm** |
| 5 — thực đơn một ngày (V60) | **đã làm** |
| 6 — sửa thực đơn đã lưu (không có migration) | **đã làm** |
| 7 — danh sách mua sắm (V63) | **đã làm** |
| 8 — nhật ký và phản hồi (V64) | **đã làm** |
| 9 — thực đơn ba và bảy ngày (không có migration) | **đã làm** |
| 10 | chưa |

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

### `GET /api/v1/allergens` và `GET /api/v1/ingredients` (thêm ở nhánh 3)

Hai danh sách chọn cho bước 2 và bước 3 của onboarding: chip thành phần gây dị ứng, và ô tìm
nguyên liệu. Cùng là dữ liệu seed dùng chung nên **công khai** như hai endpoint món ở trên.

Chúng đọc bảng của nhánh 1 nhưng ra đời ở nhánh 3, vì nhánh 1 chỉ mở endpoint cho *công thức* —
mà không có hai cái này thì giao diện phải nhúng cứng danh sách dị ứng, và nó sẽ lệch khỏi seed
ngay lần cập nhật đầu tiên.

```json
{ "data": [ { "id": "alg.giap-xac", "name": "Giáp xác (tôm, cua, ghẹ)" } ] }
```

`GET /api/v1/ingredients?q=tôm&limit=20` — `q` tìm trong cả `canonical_name` **và bảng alias**
(người dùng gõ tên địa phương vẫn phải ra); bỏ trống `q` thì trả vài dòng đầu danh mục để ô tìm
không trống trơn. `limit` 1–50, mặc định 20.

```json
{ "data": [ { "id": "ing.tom-su", "canonical_name": "Tôm sú",
              "food_group": "SEAFOOD", "allergen_status": "KNOWN" } ] }
```

`allergen_status` đi kèm để giao diện nói trước được rằng nguyên liệu **chưa được rà soát** thành
phần gây dị ứng, thay vì để người dùng ngạc nhiên khi món chứa nó bị loại (BR03).

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

## Hồ sơ ăn uống (nhánh 3)

Hai endpoint **cần JWT**. Chủ sở hữu suy từ token; đường dẫn cố ý không mang mã người dùng, nên
không có cách nào trỏ sang hồ sơ của người khác (BR19).

Hồ sơ gắn vào **thai kỳ đang hoạt động** của người gọi. Nhánh này *không* tạo lại hồ sơ thai kỳ và
không bao giờ ghi vào `app.pregnancies`: bước 1 của onboarding (ngày dự sinh **hoặc** tuần + ngày
thai) vẫn là `POST /api/v1/pregnancies` của module thai kỳ. Chưa có thai kỳ ACTIVE → `404
ACTIVE_PREGNANCY_NOT_FOUND`.

### `GET /api/v1/me/nutrition-profile`

```json
{
  "data": {
    "profile_id": "3f2a…",
    "profile_version": 3,
    "pregnancy": {
      "pregnancy_id": "3f2a…",
      "timezone": "Asia/Ho_Chi_Minh",
      "local_date": "2026-10-16",
      "estimated_due_date": "2027-04-15",
      "gestational_days": 97,
      "gestational_week": 13,
      "gestational_day": 6,
      "trimester": 1,
      "days_until_due": 183,
      "due_date_passed": false,
      "auto_plan_paused": false
    },
    "allergy_status": "KNOWN_LIST",
    "allergen_ids": ["alg.giap-xac"],
    "allergy_checked_label_allowed": true,
    "needs_allergy_confirmation": false,
    "diet_pattern": "OMNIVORE",
    "hard_exclusion_ingredient_ids": [],
    "disliked_ingredient_ids": ["ing.rau-nem"],
    "tolerance_tags": ["TANH"],
    "favorite_recipe_ids": ["rec.com-trang"],
    "blocked_recipe_ids": [],
    "daily_budget_vnd": 120000,
    "max_minutes_per_meal": 30,
    "meal_types": ["BREAKFAST", "LUNCH", "DINNER"],
    "snack_count": null,
    "energy_inputs": { "age_years": 29, "height_cm": 160.00, "activity_category": "LOW_ACTIVE" },
    "energy_estimate": { "kcal_per_day": null, "rules_version": "nutrition-seed-v3",
                         "unmet": [ { "field": "current_weight_kg", "kind": "MISSING" } ] }
  }
}
```

### `PUT /api/v1/me/nutrition-profile`

```json
{
  "data": {
    "profile_version": 4,
    "items_to_revalidate": [],
    "profile": { "...": "y hệt thân của GET" }
  }
}
```

`items_to_revalidate` **luôn rỗng ở nhánh này**. Đánh dấu mục kế hoạch tương lai là
`NEEDS_UPDATE` là việc của nhánh 10; trường có mặt từ bây giờ để nhánh đó không phải đổi hình dạng
hợp đồng đã công bố.

#### Hợp nhất, không thay thế

| Gửi gì | Nghĩa |
|---|---|
| trường vắng mặt hoặc `null` | **giữ nguyên** giá trị cũ |
| mảng rỗng `[]` | **xoá sạch** danh sách đó |
| giá trị mới | ghi đè |

Onboarding lưu theo từng bước và bước 3, 5 bỏ qua được, nên ngữ nghĩa "thay thế toàn bộ" sẽ khiến
bước 4 xoá mất những gì bước 2 vừa lưu. Đây cũng là nếp của `PATCH /api/v1/users/me/preferences`
đang có trong repo.

Không gửi `user_id` (suy từ JWT) và không gửi `profile_version` (máy chủ tự tăng).

#### `profile_version`

Tăng **đúng một** mỗi lần lưu *có thay đổi thật*, so sánh ảnh chụp trước và sau. Gửi lại y nguyên
dữ liệu cũ — người dùng bấm Lưu hai lần, hoặc mạng gửi lại — **không** tăng, vì điều đó sẽ bắt
người dùng xem lại toàn bộ thực đơn đang có ở nhánh 10 mà chẳng có gì đổi.

Hiện mọi trường của hồ sơ đều ảnh hưởng gợi ý nên đều nằm trong ảnh chụp, kể cả món yêu thích và
món bị chặn. Thêm một trường thuần hiển thị về sau thì **đừng** đưa vào ảnh chụp đó.

#### `allergy_status` — ba trạng thái, không phải boolean

| Giá trị | Nghĩa | `allergy_checked_label_allowed` |
|---|---|---|
| `KNOWN_NONE` | đã hỏi, không có dị ứng nào | `true` |
| `KNOWN_LIST` | đã hỏi, có danh sách trong `allergen_ids` | `true` |
| `UNKNOWN` | chưa hỏi, hoặc người dùng chọn "chưa rõ" — **mặc định** | `false` |

BR01: hồ sơ `UNKNOWN` **vẫn xem được** mọi nội dung và công thức, nhưng danh sách gợi ý
**không được gắn nhãn** "đã kiểm tra theo dị ứng của bạn", và `needs_allergy_confirmation` bật lên
để giao diện nhắc xác nhận trước khi lưu thực đơn cá nhân hoá. Đây là kiểm tra dữ liệu đầu vào,
**không có actor nào duyệt hồ sơ**.

Hai lượt lưu bị từ chối (`422 VALIDATION_ERROR`), vì cả hai đều tạo ra hồ sơ nói dối về chính nó:
`KNOWN_LIST` với `allergen_ids` rỗng, và `allergen_ids` không rỗng với trạng thái khác
`KNOWN_LIST`.

#### Hai danh sách nguyên liệu không phải một

| Trường | Hạng | Khi hết món |
|---|---|---|
| `hard_exclusion_ingredient_ids` | **cứng**, cùng hạng với dị ứng | không bao giờ nới ra |
| `disliked_ingredient_ids` | mềm | được mời người dùng nới ra |

Một nguyên liệu nằm cả hai chỗ → `422`: nhánh 4 sẽ vừa loại món vừa hạ điểm nó, và thông báo cho
người dùng sẽ nói hai lý do khác nhau cho cùng một món bị ẩn.

Mã lạ (nguyên liệu, dị ứng, món không có trong danh mục) bị **từ chối**, không bị lặng lẽ bỏ qua:
một mã dị ứng gõ sai mà biến mất không ai hay là cách chắc chắn nhất để người dùng tin rằng mình
đã khai xong.

#### Tuổi thai

```
tuổi thai hôm nay = số ngày thai tại mốc ghi nhận + số ngày lịch đã trôi qua
hiển thị          = floor(days / 7) tuần  và  days mod 7 ngày
```

Đếm theo **ngày lịch trong múi giờ người dùng** (dùng lại `CalendarZone` của module lịch), không
theo giờ UTC. `trimester` đổi đúng ranh giới tuần: 13 tuần 6 ngày vẫn là 1, 14 tuần 0 ngày đã là 2
(TC04). Mốc neo lấy theo thứ tự: mốc thủ công → ngày đầu kỳ kinh cuối → ngày dự sinh quy đổi theo
280 ngày.

`trimester` (1/2/3) là giai đoạn **để hiển thị**. Bộ hệ số của công thức EER là một con số khác
(`FIRST` | `SECOND_THIRD`) và nằm ở `energy_estimate.trimester_group`, cố ý **không** lặp lại
trong khối `pregnancy`: hai trường tên gần giống nhau nằm cạnh nhau là chỗ chắc chắn sẽ có người
dùng nhầm.

Qua ngày dự sinh thì `due_date_passed` và `auto_plan_paused` bật lên — nhưng **không** đồng nghĩa
đã sinh: hồ sơ vẫn đọc được nguyên vẹn, chỉ dừng *tự* tạo kế hoạch mới và để giao diện hỏi lại
trạng thái. Ngày dự sinh người dùng đã xác nhận không bao giờ bị tự thay bằng giá trị khác.

#### Bước 5 bỏ qua được (TC05)

Không khai tuổi, chiều cao, cân nặng, nhóm vận động thì `energy_inputs` là `null` và
`energy_estimate.kcal_per_day` là `null` kèm `unmet` chỉ đúng tên trường còn thiếu. Giao diện lúc
đó **không hiển thị phần trăm hoàn thành năng lượng** và không mượn mặc định 2.000 kcal (BR20).
Mọi thứ khác — xem danh mục, lưu hồ sơ, dùng chế độ nhanh — vẫn chạy bình thường.

#### Bảng dữ liệu (V58)

| Bảng | Vai trò |
|---|---|
| `app.eating_preferences` | hồ sơ, khoá bằng `app.pregnancies.id`; giữ `allergy_status` và `profile_version` |
| `app.user_allergens` | dị ứng đã khai, unique `(profile_id, allergen_id)` |
| `app.eating_preference_hard_exclusions` · `_disliked_ingredients` · `_tolerance_tags` · `_meal_types` | bốn danh sách con |
| `app.user_recipe_preferences` | `FAVORITE` \| `BLOCKED`, unique `(user_id, recipe_id)` |

`app.user_recipe_preferences` **không có cột `expires_at`** (QĐ-03), dù đặc tả gốc có. Đó là lựa
chọn chủ động, sống đến khi người dùng tự đổi; phản hồi *có hạn* sau bữa ăn thuộc
`app.meal_feedbacks` ở nhánh 8. Thêm lại cột đó là làm sống lại đúng chỗ trùng lặp đã bỏ.

`profile_version` nằm ở `app.eating_preferences`, **không** ở `app.pregnancies`: bảng kia thuộc
module thai kỳ và đã có cột `version` làm optimistic lock, thêm một cột version thứ hai mang nghĩa
khác vào đó thì hai module phải cùng giữ một bất biến mà không bên nào thấy hết.

Khoá của `user_recipe_preferences` là `user_id` chứ không phải `profile_id`, đúng đặc tả: "tôi
không thích món này" không mất đi khi mẹ bầu bắt đầu một thai kỳ mới.

---

## Gợi ý món (nhánh 4)

**Cần JWT.** Không có phiên bản ẩn danh: gợi ý đọc hồ sơ ăn uống của người gọi (QĐ-06).

```
POST /api/v1/recommendations
```

`POST` chứ không `GET` vì yêu cầu chở theo lịch sử gần đây và bộ đếm trong tuần — những thứ nhánh 5
truyền vào từ kế hoạch *đang dựng dở*. Lượt gọi cũng ghi một dòng nhật ký, nên nó không phải thao
tác đọc thuần.

### Yêu cầu

```json
{
  "meal_types": ["LUNCH", "DINNER"],
  "role": "MAIN_DISH",
  "limit": 5,
  "seed": 4242,
  "strict_time": false,
  "strict_budget": false,
  "recent_recipe_ids": ["rec.ga-kho-gung"],
  "yesterday_main_ingredient_tags": ["ing.thit-ga-uc"],
  "week_recipe_counts": { "rec.com-trang": 2 },
  "request_id": "req-1"
}
```

| Tham số | Ý nghĩa |
|---|---|
| `meal_types` | 1–4 loại bữa. Mỗi loại trả một danh sách **độc lập** |
| `role` | lọc theo vai trò trong bữa; bỏ trống = mọi vai trò |
| `limit` | 1–20, mặc định 5 |
| `seed` | nút "Tạo lại". Bỏ trống = thứ tự chuẩn; cùng seed cho đúng danh sách cũ |
| `strict_time` · `strict_budget` | **mặc định false** — thời gian và chi phí là ưu tiên *mềm*, chỉ thành bộ lọc cứng khi người dùng tự bật (BR13) |
| ba trường lịch sử | do **người gọi** cung cấp, không phải service tự tra — xem bên dưới |
| `request_id` | mã tương quan để tra nhật ký. **Không phải** idempotency key |

**Vì sao lịch sử do người gọi truyền vào:** kế hoạch và nhật ký thuộc nhánh 5 và 8. Ngay cả khi đã
có, `PlanGenerator` cần chấm điểm trên kế hoạch *đang dựng dở* chứ không phải trên thứ đã lưu trong
database. Để người gọi truyền vào là cách duy nhất phục vụ cả hai tình huống bằng một đường.

### Phản hồi

```json
{
  "data": {
    "profile_version": 3,
    "rules_version": "nutrition-seed-v3",
    "seed": 4242,
    "allergy_checked": false,
    "notes": [
      { "code": "ALLERGY_STATUS_UNCONFIRMED",
        "message": "Bạn chưa xác nhận tình trạng dị ứng, danh sách này chưa lọc theo dị ứng" }
    ],
    "meals": [
      {
        "meal_type": "LUNCH",
        "status": "READY",
        "notes": [],
        "criteria_used": ["C", "N", "P", "V"],
        "nutrition_status": "PARTIAL",
        "items": [
          {
            "recipe_id": "rec.rau-muong-luoc",
            "name": "Rau muống luộc",
            "role": "VEGETABLE",
            "minutes": 10,
            "nutrition_status": "PARTIAL",
            "reasons": [{ "code": "QUICK_TO_COOK", "message": "Khoảng 10 phút nấu" }]
          }
        ],
        "excluded": [
          { "recipe_id": "rec.canh-bi-do-tom", "name": "Canh bí đỏ nấu tôm",
            "status": "BLOCKED",
            "reasons": [{ "code": "ALLERGEN_MATCH",
                          "message": "Có Tôm sú — thành phần bạn đã khai dị ứng" }] }
        ],
        "log_id": "7f1c…"
      }
    ]
  }
}
```

**Cố ý không có điểm số.** Điểm thuật toán giữ ở backend: đưa ra giao diện thì sớm muộn nó bị đọc
thành "điểm sức khỏe" của món, mà nó không phải thứ đó. Cái hiển thị được là **thứ tự** và
`reasons`. Điểm từng thành phần nằm trong `app.recommendation_log_entries`.

`allergy_checked: false` → giao diện **không được gắn nhãn** "đã kiểm tra theo dị ứng của bạn"
(BR01). Hồ sơ `UNKNOWN` vẫn nhận gợi ý bình thường — không có gì để lọc thì cũng không lọc sai.

`status: "NO_MATCH"` trả **HTTP 200**, không phải mã lỗi: hết món hợp lệ là kết quả nghiệp vụ của
một hồ sơ nhiều ràng buộc. Giao diện mời nới các ưu tiên **mềm** (thời gian, ngân sách, món không
thích) — không bao giờ nới dị ứng hay chế độ ăn bắt buộc (bất biến 5).

### Hai bước, nối tiếp

```
Hồ sơ + danh mục → EligibilityFilter → Ranker → (nhánh 5: PlanGenerator)
```

Món chưa qua lọc **không bao giờ** được chấm điểm (bất biến 2). `ProfileConstraints` — thứ bước lọc
nhận vào — *không có chỗ nào* để truyền món yêu thích, nên sở thích không thể vượt qua một điều
kiện dị ứng kể cả khi ai đó muốn (BR05, TC02).

#### Bước lọc — `eligibility`

| Giá trị | Nghĩa |
|---|---|
| `ELIGIBLE` | đã kiểm đủ, không vi phạm |
| `BLOCKED` | đã biết rõ và vi phạm |
| `UNKNOWN` | **chưa đủ dữ liệu để kết luận** — có nguyên liệu chưa rà soát (BR03) |

`BLOCKED` và `UNKNOWN` **không được gộp**: gộp lại thì không nói được với người dùng rằng món vắng
mặt vì *dữ liệu còn thiếu* chứ không phải vì nó không hợp với họ.

Mọi phép kiểm — dị ứng, nguyên liệu phải tránh, chế độ ăn, nguyên liệu chưa rà soát — đều duyệt
**đệ quy qua nguyên liệu con** (BR02). Nước dùng nấu bằng tôm khô chặn món với người dị ứng tôm dù
công thức không hề viết chữ "tôm" (TC01); nước mắm mang nhóm `SEASONING` vẫn bị chế độ ăn chay
trường loại vì bên trong là cá cơm.

Thứ tự kiểm có chủ ý — món vừa chứa thành phần gây dị ứng vừa có nguyên liệu chưa rà soát ra
`BLOCKED` chứ không `UNKNOWN`: đã biết là vi phạm thì không được hạ xuống thành "chưa rõ".

> **Bộ lọc chế độ ăn đang chặt hơn mức cần, và nói ra điều đó.** `FoodGroup.MEAT_FISH_EGG` gộp
> thịt, cá và trứng, nên không tách được "ăn chay có trứng sữa" khỏi "không ăn thịt". Chọn hướng
> chặt vì đây là điều kiện cùng hạng với dị ứng, và kèm `DIET_FILTER_COARSE` trong `notes` thay vì
> im lặng. Gỡ được khi danh mục có nhãn chế độ ăn riêng cho từng nguyên liệu.

#### Bước chấm điểm

```
S = 0,35·N + 0,30·P + 0,15·V + 0,10·B + 0,10·C
```

Mỗi thành phần 0–100. **Cấu hình thử nghiệm sản phẩm, không phải công thức y khoa.**

| | Nội dung | Khi không dùng được |
|---|---|---|
| N | khớp phần năng lượng và vi chất tham chiếu của bữa | chưa có dữ liệu vi chất nào và không có `e_slot` |
| P | khẩu vị: 50 ± yêu thích, nguyên liệu không thích, phản hồi | luôn dùng được |
| V | đa dạng: `100 − 40·I_recipe − 20·I_mainIngredient − 10·n_week` | luôn dùng được |
| B | chi phí so với ngân sách bữa | người dùng bỏ qua ngân sách, **hoặc món chưa có giá** |
| C | thời gian nấu so với giới hạn | người dùng bỏ qua giới hạn thời gian |

Thiếu tiêu chí thì **bỏ trọng số rồi chuẩn hóa lại**, không bao giờ cho 0 điểm:

```
S = Σ(w_i · f_i) / Σ(w_i),   i ∈ tập tiêu chí dùng được
```

Cho 0 nghĩa là coi "chưa biết" bằng "kém", và như thế mọi món thiếu dữ liệu sẽ nằm đáy danh sách
chỉ vì danh mục còn trống. `criteria_used` trong phản hồi nói đúng tập nào đã tham gia.

Ví dụ kiểm tra phải khớp từng chữ số: 80/95/70/90/85 → **84,5** · 90/60/90/70/70 → **77,0** ·
70/80/100/100/90 → **82,5** (TC07). Bỏ ngân sách khỏi dòng đầu → 75,5 / 0,90 = **83,889**, cao hơn
hẳn cách làm sai (cho B = 0 ra 75,5) — đó chính là TC06.

`N` nhân thêm **hệ số chất lượng dữ liệu** `coverage_ratio = số chất có dữ liệu / số chất cấu hình`,
áp **theo từng nhánh** (vi chất, năng lượng). Thiếu nó, một món khai đúng một vi chất và khai vừa
đủ sẽ được 100 điểm dinh dưỡng, cao hơn món khai đủ cả sáu chất. Hệ số này **không phải** cách thay
giá trị thiếu bằng 0 — mọi `q_k` thiếu vẫn là `null` ở mọi nơi khác.

> **Hai thành phần của P hiện ngủ đông.** "Hợp nhóm ẩm thực thích" (+15) và "hợp tags dung nạp"
> (+15) đòi công thức phải mang nhãn nhóm ẩm thực và nhãn mùi/kết cấu; danh mục của nhánh 1 không
> có hai nhãn đó. Công thức vẫn hiện thực đủ và có test, nhưng đầu vào luôn rỗng. **Cố ý không suy
> chúng từ nguyên liệu chính** — suy bừa thì điểm khẩu vị sẽ nói dối rất thuyết phục.

**Bằng điểm** → ưu tiên món được chấm trên nhiều tiêu chí hơn (hệ thống biết nó rõ hơn), rồi
`seed`, rồi `recipe_id`. `seed` **chỉ chạm tới các món bằng điểm**; nếu nó được phép chạm tới cả
thứ hạng theo điểm thì không còn gì để chứng minh.

#### Lý do hiển thị

Tối đa **hai** mỗi món, **luôn có ít nhất một**, và tất cả lấy từ dữ liệu thật (BR15). Danh sách mã
là một tập đóng (`RecommendationReason`): mỗi mã chỉ dựng được khi nơi phát hiện cầm sẵn mẩu dữ
liệu điền vào mẫu câu — tên nguyên liệu, số phút, tên chế độ ăn. **Không có chỗ nào sinh câu tự
do**, nên cũng không cần dịch vụ AI bên ngoài.

Dự phòng khi món không có lý do tích cực nào là thời gian nấu — nó đọc thẳng từ cột `minutes` nên
luôn có và luôn đối chiếu được. Không bao giờ rơi về một câu chung chung kiểu "món phù hợp với
bạn".

### Nhật ký gợi ý (V59)

| Bảng | Vai trò |
|---|---|
| `app.recommendation_logs` | một dòng mỗi lượt: `profile_version`, `rules_version`, `seed`, `status`, `criteria_used` |
| `app.recommendation_log_entries` | một dòng mỗi món ứng viên, **kể cả món bị loại** |

Bộ ba `(profile_version, rules_version, seed)` là điều kiện để dựng lại đúng thứ hạng cũ. Ghi cả
món bị loại vì chỉ ghi món thắng thì mất nửa phần giải thích: người dùng hỏi "sao không thấy món cá
hồi" và không ai trả lời được.

Điểm thành phần `NULL` nghĩa là tiêu chí **không dùng được**, không phải 0 điểm. Database khóa bất
biến 2 bằng một CHECK: món không `ELIGIBLE` thì không được mang `score_total` hay `rank`.

Cố ý là bảng con chứ không phải ba cột JSON như đặc tả phác ra — "ghi đủ `feature_scores` để tái lập
xếp hạng" chỉ có nghĩa khi truy vấn được bằng một câu `SELECT`.

### Chi phí món

`RecipeCostEstimator` chỉ quy đổi những đơn vị **thực sự là khối lượng** (`kg`, `g`) và **bỏ cuộc cả
món** ngay khi gặp một nguyên liệu chưa có giá hoặc có giá theo `lít`/`quả`. Bỏ cuộc cả món chứ
không bỏ qua dòng đó: cộng tổng trên một nửa số nguyên liệu cho ra con số thấp hơn thực tế mà nhìn
vẫn như số đầy đủ, rồi điểm B sẽ khen một món đắt là rẻ. Không quy được giá → **B không hoạt
động**, không phải B = 0. Đoán một mật độ chung để quy "lít" sang gram chính là lỗi đặc tả cấm.

---

## Thực đơn một ngày (nhánh 5)

Ba endpoint, **đều cần JWT**. Chủ sở hữu luôn suy từ `jwt.sub`, không bao giờ từ body.

| Endpoint | Việc |
|---|---|
| `POST /api/v1/meal-plans/preview` | tạo bản xem trước, sống **30 phút** |
| `POST /api/v1/meal-plans` | lưu bản xem trước thành kế hoạch (201) |
| `GET /api/v1/meal-plans/{planId}` | đọc kế hoạch đã lưu kèm tổng dự kiến |

Phần này mô tả **một ngày**. `day_count` nhận 1, 3 hoặc 7 — ba và bảy ngày dùng đúng ba endpoint
này và đúng hình dạng phản hồi này, phần riêng của chúng ở [nhánh 9](#thực-đơn-ba-và-bảy-ngày-nhánh-9).

### Yêu cầu tạo bản xem trước

```json
{
  "request_id": "req_demo_001",
  "profile_version": 3,
  "start_date": "2026-10-14",
  "day_count": 1,
  "meal_types": ["BREAKFAST", "LUNCH", "DINNER", "SNACK"],
  "snack_count": 1,
  "constraints": {
    "daily_budget_vnd": 120000,
    "max_minutes_per_meal": 30,
    "strict_budget": false,
    "strict_time": false
  },
  "locked_items": [],
  "seed": 17
}
```

| Trường | Ý nghĩa |
|---|---|
| `request_id` | **idempotency key**, bắt buộc. Gửi lại cùng giá trị trả lại **đúng bản xem trước cũ** |
| `profile_version` | phiên bản hồ sơ giao diện đang cầm; lệch → `VERSION_CONFLICT`, không lập thực đơn trên dữ liệu cũ. Bỏ trống = không kiểm |
| `snack_count` | 1–3, mặc định 1. Tỉ lệ 15% của bữa phụ **chia đều** cho từng bữa (QĐ-02) |
| `constraints.*` | ngân sách và thời gian phải **> 0** — cả hai nằm dưới mẫu số một phép chia |
| `strict_budget` / `strict_time` | mặc định false: hai thứ này là ưu tiên **mềm** trừ khi người dùng tự bật (BR13) |
| `locked_items[].servings` | phải > 0, báo lỗi **tại trường** |
| `seed` | để tái lập kết quả thử nghiệm. **Không phải dữ liệu sức khỏe**, như `request_id` |

### Phản hồi

```json
{
  "data": {
    "preview_id": "0f0b…",
    "status": "READY_WITH_NOTES",
    "profile_version": 3,
    "rules_version": "nutrition-seed-v3",
    "seed": 17,
    "nutrition_coverage": "PARTIAL",
    "expires_at": "2026-10-14T04:30:00Z",
    "notes": [
      { "code": "NUTRITION_DATA_INCOMPLETE", "message": "Một số chất chưa đủ dữ liệu…" }
    ],
    "days": [
      {
        "local_date": "2026-10-14",
        "slots": [
          {
            "meal_type": "LUNCH", "sequence": 1,
            "template_code": "LUNCH:ROLE_SET", "mode": "ROLE_SET",
            "required_roles": ["STAPLE", "PROTEIN", "VEGETABLE"],
            "filled": true,
            "items": [
              { "recipe_id": "rec.com-trang", "name": "Cơm trắng", "role": "STAPLE",
                "recipe_version": 1, "servings": 1.0, "serving_gram": 180.00,
                "minutes": 25, "locked": false,
                "estimated_cost_vnd": 1240.00, "reason_codes": [] }
            ],
            "reason_codes": []
          }
        ],
        "nutrition": { "ENERGY": { "known_value": 612.5, "unit": "kcal", "coverage": "COMPLETE" } },
        "method_notes": [],
        "estimated_cost_vnd": 36500.00
      }
    ]
  }
}
```

Khối `nutrition` đúng hình dạng đã công bố ở nhánh 2: `known_value` **null vẫn xuất hiện**, và
`estimated_cost_vnd` null nghĩa là **chưa quy đổi được giá** cho ít nhất một món — không phải 0 đồng.

Hai trường của mục, thêm ở nhánh 6: `item_id` là địa chỉ để sửa đúng mục đó, chỉ có ở **kế hoạch đã
lưu** (bản xem trước chưa có dòng nào trong database để trỏ tới); `serving_gram` là khối lượng một
phần, null nghĩa là **món chưa cân được**, và giao diện chỉ mở ô nhập theo gram khi nó có số.

**Phản hồi cố ý không mang điểm số.** `J` và năm phần phạt giữ ở backend, đúng như điểm món ở nhánh 4.

### Ba trạng thái, đừng lẫn ba nhóm

| Nhóm | Giá trị | Nói về |
|---|---|---|
| `status` / `generation_status` | `READY` · `READY_WITH_NOTES` · `NO_MATCH` | kết quả **lượt sinh** |
| `lifecycle_state` | `SAVED` · `NEEDS_UPDATE` | trạng thái **lưu trữ** (nhánh 10 đổi) |
| `nutrition_coverage` | `COMPLETE` · `PARTIAL` | độ đầy đủ **dữ liệu** |

Một thực đơn `READY` vẫn có thể `PARTIAL`. **`READY` không phải chứng nhận y tế.**
`NO_MATCH` chỉ tồn tại ở bản xem trước — bảng `app.meal_plans` từ chối nó bằng một CHECK.

`READY_WITH_NOTES` khi còn ưu tiên mềm chưa trọn hoặc còn thiếu dữ liệu. `PARTIAL_DAY_PLAN`
**không** hạ trạng thái: lập một phần ngày là lựa chọn của người dùng, không phải khiếm khuyết.

### Mã lý do mức kế hoạch (HTTP 200, không phải lỗi)

| Mã | Khi nào |
|---|---|
| `NO_MATCH` | không hoàn thành được vị trí theo điều kiện cứng |
| `ROLE_NO_CANDIDATE` | nêu rõ **vai trò nào, bữa nào, bộ lọc nào** đã chặn (TC18) |
| `SEARCH_LIMIT` | hết hạn tìm kiếm — **không** kết luận không tồn tại nghiệm (TC19) |
| `PARTIAL_DAY_PLAN` | chỉ lập một phần các bữa (TC23) |
| `NUTRITION_DATA_INCOMPLETE` | tổng ngày chỉ gồm phần đã biết |
| `DAY_OVER_BUDGET` · `MEAL_OVER_TIME` | vượt giới hạn **mềm** |
| `STRICT_BUDGET_WITHOUT_PRICE` | bật ngân sách cứng mà còn món chưa có giá — mời bổ sung giá **hoặc** tắt giới hạn, người dùng chọn |
| `LOCKED_ITEM_UNPLACEABLE` | món khóa không hợp cấu trúc nào của bữa đó |

### Tạo cấu trúc trước, chọn món sau

Mỗi bữa chọn **một** cấu trúc trong các template của nó (ưu tiên cấu trúc ít món hơn), và chỉ nhận
cấu trúc mà **mọi** vai trò đều còn ứng viên — BR06: một món hoàn chỉnh **hoặc** một tập vai trò,
không trộn. Không cấu trúc nào khả thi → `NO_MATCH`, không bao giờ ghép tạm vai trò của hai cấu trúc.

Món khóa đi qua **đúng bước lọc** của mọi món khác (bất biến 2). Món khóa bị lọc chặn làm **cả bữa**
thành `NO_MATCH` chứ không bị lặng lẽ thay bằng món khác: người dùng đã nói rõ họ muốn giữ món đó.

### Beam search và hàm mục tiêu

```
J = 0,40·Nutrition + 0,25·Preference + 0,15·Variety + 0,10·Cost + 0,10·Time
```

Chọn **J nhỏ hơn**. Mỗi thành phần chặn vào 0–1; **tiêu chí không có dữ liệu thì bỏ cả trọng số**
rồi chuẩn hóa trên phần còn lại — không bao giờ cho 0, cũng không bao giờ coi là đã đạt.

| Tham số | Giá trị |
|---|---|
| Ứng viên mỗi vị trí sau lọc | 15, mở rộng lên 30 khi thiếu phương án |
| Beam giữ sau mỗi bước | 20 |
| Hạn thời gian một ngày | 2 giây (ba và bảy ngày: 5 giây cho cả kế hoạch — nhánh 9) |

Vi chất chỉ tính trên chất mang `COMPLETE`: một chất mà vài nguyên liệu chưa khai số liệu thì tổng
của nó thấp hơn thực tế, và phạt theo con số đó sẽ đẩy bộ sinh đi chọn thêm món để bù một thiếu hụt
**của dữ liệu** chứ không phải của bữa ăn.

Đa dạng (`V`) **chấm lại sau từng lựa chọn** theo chính phương án đang dựng, không lấy từ một danh
sách điểm cố định. Khẩu phần lấy từ tập cho phép của template — bộ sinh **không** nhân khẩu phần
liên tục để ép đủ vi chất.

**TC23:** mục tiêu năng lượng và vi chất nhân với **tổng tỉ lệ của đúng những bữa được lập**. Chỉ lập
trưa và tối thì mục tiêu là 60% mức ngày, không chuẩn hóa hai bữa đó lên thành cả ngày. Ngân sách thì
ngược lại, giữ nguyên mức ngày người dùng khai.

Giới hạn thời gian kiểm ở mức **toàn bữa** (tổng thời gian các món — ước tính bảo thủ, chưa giả định
nấu song song), còn ngân sách cứng kiểm trên **cả ngày**. Hiển thị vẫn ở mức món.

**Thời gian sinh thực tế** (Windows 11, JDK 21, H2 profile test, 16/10/2026): cấu trúc nặng nhất
nhánh này sinh ra — 6 bữa, 11 vị trí, 30 ứng viên và 4 mức khẩu phần mỗi vị trí — mất **428 ms**
trong bộ sinh; một lượt `POST /meal-plans/preview` đầu tiên cho ngày đầy đủ trên seed thật, tính cả
HTTP và JPA, mất **648 ms**. Cả hai nằm trong mục tiêu 2 giây. Đo lại ngày 19/10/2026 sau khi
nhánh 9 vào: **859 ms** cho cùng lượt một ngày — xem bảng ở [nhánh 9](#hiệu-năng).

### Lưu và đọc lại

`POST /api/v1/meal-plans` chỉ nhận `preview_id` — nội dung đã nằm trong bảng, nhận lại nội dung từ
client là mở đúng một đường để lưu một thực đơn chưa qua bước lọc. Kiểm **lại** bốn điều kiện:

| Điều kiện | Lệch thì |
|---|---|
| bản xem trước còn hạn (30 phút) | `VERSION_CONFLICT` |
| `profile_version` khớp hiện tại | `VERSION_CONFLICT` |
| `rules_version` khớp hiện tại | `VERSION_CONFLICT` |
| từng công thức còn đúng `recipe_version` | `VERSION_CONFLICT` |

Bản `NO_MATCH` không có gì để lưu → `VALIDATION_ERROR`. Lưu hai lần cùng một `preview_id` trả về
**đúng kế hoạch đã tạo** (`saved_plan_id` trên bản xem trước), không tạo bản thứ hai.

### Bảng (V60)

| Bảng | Vai trò |
|---|---|
| `app.meal_plans` | kế hoạch đã lưu: khoảng ngày, hai nhóm trạng thái, bốn con số phiên bản |
| `app.meal_slots` | vị trí bữa; unique **bộ bốn** `(plan_id, local_date, meal_type, sequence)` (QĐ-02) |
| `app.meal_plan_items` | món đã chọn kèm `nutrient_snapshot` và `cost_snapshot` |
| `app.meal_plan_previews` | bản xem trước, unique `(user_id, request_id)`, TTL 30 phút (QĐ-01) |

`meal_slots.sequence`: bữa chính luôn 1, bữa phụ 1–3 — khóa bằng CHECK ở database chứ không chỉ ở
service.

`nutrient_snapshot` lưu **snapshot đầu vào** (`RecipeSnapshot` của nhánh 2: nguyên liệu, gram, bảng
dinh dưỡng), không lưu con số tổng. Tổng được dựng lại bằng chính `NutritionCalculator` mỗi lần đọc,
nên **chỉ có một bộ tính** (bất biến 4) và tổng API trả về không thể lệch với tổng tính từ snapshot
các mục — kể cả sau khi seed sửa công thức (BR17).

Bản xem trước hết hạn được **xóa lười lúc đọc**: gửi lại cùng `request_id` sau 30 phút thì bản chết
bị xóa và một lượt tìm mới chạy, thay vì khóa `request_id` vĩnh viễn.

---

## Sửa thực đơn đã lưu (nhánh 6)

Bốn endpoint, **đều cần JWT**, đều trên một kế hoạch đã lưu. Mục tiêu: sửa được **một** lựa chọn mà
không phải tạo lại toàn bộ kế hoạch.

| Endpoint | Việc |
|---|---|
| `GET /api/v1/meal-plans/{planId}/items/{itemId}/alternatives` | lựa chọn cùng bữa, cùng vai trò |
| `PATCH /api/v1/meal-plans/{planId}/items/{itemId}` | đổi món / số phần / trạng thái giữ |
| `POST /api/v1/meal-plans/{planId}/regenerate` | tạo lại các mục **chưa giữ** |
| `POST /api/v1/meal-plans/{planId}/undo` | khôi phục bản ngay trước thao tác gần nhất |

**Không có migration.** Nhánh này chạy trên đúng bốn bảng V60 của nhánh 5.

### Hai con số phiên bản, bắt buộc cả hai

Ba endpoint ghi đều đòi `expected_plan_version` **và** `expected_profile_version`.

| Trường | Lệch thì | Vì sao |
|---|---|---|
| `expected_plan_version` | `VERSION_CONFLICT` (409) | thiết bị khác đã sửa; tải lại rồi chọn lại, **không ghi đè** |
| `expected_profile_version` | `VERSION_CONFLICT` (409) | danh sách lựa chọn người dùng vừa nhìn thấy dựng trên hồ sơ cũ |

Yêu cầu bị từ chối **không thay dữ liệu và không tăng phiên bản** (TC14). Kế hoạch được đọc bằng
`SELECT … FOR UPDATE`, nên hai yêu cầu song song không cùng thấy một phiên bản rồi cùng ghi đè.

Hoàn tác **vẫn tăng** `plan_version`. Khôi phục nội dung cũ không phải quay ngược đồng hồ: hạ số
phiên bản sẽ cho một thiết bị đang cầm số cũ ghi đè thành công lên chính thao tác hoàn tác.

### `PATCH` — đổi món, số phần, hoặc giữ món

```json
{
  "expected_plan_version": 4,
  "expected_profile_version": 3,
  "recipe_id": "rec.ca-hoi-ap-chao",
  "recipe_version": 1,
  "servings": 1.5,
  "locked": false
}
```

| Trường | Ý nghĩa |
|---|---|
| `recipe_id` | món thay thế; bỏ trống = giữ món cũ. Gửi lại đúng mã đang có **không** tính là đổi món |
| `recipe_version` | phiên bản giao diện đang hiển thị; lệch → `VERSION_CONFLICT` |
| `servings` | số phần, > 0 và trong miền khẩu phần của cấu trúc bữa (BR07) |
| `grams` | thay cho `servings` khi nhập theo gram; quy đổi bằng `serving_gram` của chính món đó |
| `locked` | giữ / bỏ giữ mục này cho lượt tạo lại |

Trường nào bỏ trống thì giữ nguyên. Yêu cầu **không nêu thay đổi nào** bị từ chối: tăng
`plan_version` suông sẽ làm thiết bị kia nhận 409 cho một thao tác chưa từng xảy ra.

**Miền khẩu phần là một khoảng, không phải bốn nấc.** Giao diện cho chọn 0,5 · 1 · 1,5 · 2 theo
`serving_options` của cấu trúc bữa, nhưng ô nhập gram quy ra số phần lẻ, nên điều kiện kiểm là
`min ≤ servings ≤ max` của tập đó. Ngoài khoảng → `VALIDATION_ERROR` kèm tên trường.

**Chi phí đi theo nguồn của nó.** Đổi riêng khẩu phần thì `cost_snapshot` được nhân lại từ đơn giá
đã chụp lúc chọn món — đọc bảng giá hôm nay ở đây sẽ làm con số nhảy vì một lý do người dùng không
hề thao tác. Đổi món thì giá lấy mới, vì đó là một món khác.

### `GET …/alternatives`

```json
{
  "data": {
    "item_id": "7f4a…", "meal_type": "LUNCH", "sequence": 1, "role": "VEGETABLE",
    "current_recipe_id": "rec.rau-muong-luoc", "current_servings": 1.0,
    "serving_options": [0.5, 1, 1.5, 2],
    "alternatives": [
      { "recipe_id": "rec.canh-bi-do-tom", "name": "Canh bí đỏ nấu tôm", "recipe_version": 1,
        "minutes": 25, "serving_gram": 200.00, "estimated_cost_vnd": 9800.00,
        "delta_cost_vnd": 4200.00, "delta_minutes": 15 }
    ],
    "notes": []
  }
}
```

Danh sách dựng lại **từ hồ sơ hiện tại**, không tin kết quả lọc của lúc lập thực đơn: một món lọt
vào kế hoạch từ trước khi mẹ bầu khai thêm dị ứng sẽ không xuất hiện ở đây (bất biến 2 và 5). Món
đang nằm trong bữa bị loại khỏi danh sách.

Chênh lệch tính ở **đúng số phần hiện tại** của mục. `delta_cost_vnd` null khi **một trong hai bên**
chưa có giá — trừ một con số cho một khoảng trống rồi gọi kết quả là "không chênh lệch" là nói dối
về dữ liệu chưa có.

Danh sách rỗng vẫn là **HTTP 200** kèm `NO_MATCH` và `ROLE_NO_CANDIDATE` nêu rõ bộ lọc nào đã chặn.

### `POST …/regenerate`

```json
{ "expected_plan_version": 4, "expected_profile_version": 3,
  "constraints": { "daily_budget_vnd": 120000 }, "seed": 17 }
```

Giữ nguyên **cấu trúc ngày** đã lưu — vẫn đúng template trong `template_code`. Tạo lại là tìm lựa
chọn khác trong cùng cấu trúc, không phải dựng một bữa khác hình dạng (BR06). Muốn đổi tập bữa thì
đường đi là `POST /meal-plans/preview`.

- Mục đã giữ **không bị xóa rồi tạo lại**: dòng cũ ở nguyên đó, nên `item_id`, `recipe_version` và
  số phần của nó không có cơ hội bị viết lại (TC11).
- Mục đã giữ **không còn hợp lệ** sau khi hồ sơ đổi → `RECIPE_NOT_ELIGIBLE` (422) kèm `item_id`,
  **không âm thầm mở khóa**. Người dùng đã nói họ muốn giữ món đó.
- Không có cách hoàn thành quanh các mục đã giữ → **HTTP 200**, `changed: false`, kế hoạch nguyên
  vẹn, và `notes` liệt kê chính các mục đó để người dùng chọn mục muốn bỏ giữ.

`constraints` bỏ trống thì lấy ngân sách và thời gian từ hồ sơ ăn uống; `strict_*` vẫn mặc định tắt
(BR13).

### `POST …/undo`

Một **bước lùi**, không phải một ngăn xếp lịch sử: bản chụp bị xóa sau khi dùng, nên hoàn tác hai
lần liên tiếp nhận `RESOURCE_NOT_FOUND` (404).

Hoàn tác **kiểm tra lại điều kiện hiện tại trước khi khôi phục**. Món từng hợp lệ mà nay vi phạm dị
ứng thì không được quay lại kế hoạch chỉ vì nó đã từng ở đó → `RECIPE_NOT_ELIGIBLE` (422), và kế
hoạch giữ nguyên trạng thái an toàn hiện tại.

#### Bản hoàn tác nằm ở đâu, khi nhánh không có migration

`PlanRevision` được ghi vào chính `app.meal_plan_previews` với `request_id = "undo:" + plan_id` —
bảng đó sẵn là "một thực đơn dạng JSON, có hạn dùng, thuộc về một người, trỏ được tới một kế
hoạch". Unique `(user_id, request_id)` cho mỗi kế hoạch đúng một hàng, và đó chính là một bước lùi.
TTL **24 giờ**: hoàn tác là thao tác ngay sau khi lỡ tay, không phải một kho lịch sử.

Bản chụp mang theo cả `nutrient_snapshot` của từng mục, nên khôi phục trả lại **đúng con số đã hiển
thị**, kể cả khi seed đã đổi công thức trong lúc đó (BR17).

Hệ quả cho client: `request_id` của `POST /meal-plans/preview` **không được bắt đầu bằng `undo:`** →
`VALIDATION_ERROR` kèm trường `request_id`.

### Phản hồi của ba endpoint ghi

```json
{
  "data": {
    "plan": { "plan_id": "…", "plan_version": 5, "generation_status": "READY_WITH_NOTES",
              "nutrition_coverage": "PARTIAL", "notes": [], "days": [ … ] },
    "changed": true,
    "shopping_list_version": 5,
    "undo_available": true,
    "notes": []
  }
}
```

**Trả về cả kế hoạch, không riêng mục vừa đổi.** BR12 — đổi một món tính lại tổng của ngày; trả về
riêng mục sẽ buộc giao diện tự cộng lại tổng, tức là tạo nguồn sự thật thứ hai cho con số dinh dưỡng
(bất biến 4). Một yêu cầu, một transaction, một bức tranh đã tính lại.

| Trường | Ý nghĩa |
|---|---|
| `plan.notes` | trạng thái của **kế hoạch** sau thao tác: vượt ngân sách ngày, bữa quá giờ, dữ liệu thiếu |
| `notes` | kết quả của **thao tác này**: vì sao không đổi được gì |
| `changed` | false = kế hoạch giữ nguyên, `plan` là bản cũ |
| `shopping_list_version` | bằng `plan_version`: danh sách mua sắm là hàm thuần của các mục kế hoạch, nên nó đổi đúng khi và chỉ khi kế hoạch đổi. Nhánh 7 dựng trên chính con số này thay vì đếm một phiên bản thứ hai có thể lệch |

`generation_status` được tính lại sau mỗi thao tác bằng đúng hàm của nhánh 5: hết ghi chú thì kế
hoạch quay về `READY`.

### Kiểm toàn kế hoạch sau mỗi thao tác (BR12)

Ngân sách là giới hạn của **ngày** và thời gian là giới hạn của **một bữa**, nên không mục nào tự nó
vi phạm được — đổi sang một món rẻ hơn vẫn có thể đẩy cả ngày vượt ngân sách nếu nó kéo theo khẩu
phần lớn hơn. Vì vậy mọi thao tác sửa đều tính lại `DAY_OVER_BUDGET` và `MEAL_OVER_TIME` trên toàn
kế hoạch, không chỉ trên mục vừa chạm. Đây là **ưu tiên mềm chưa trọn**, không phải lỗi: kế hoạch
vẫn lưu được và vẫn dùng được (BR13).

Kế hoạch của nhánh này dài một ngày, nên "các điều kiện liên quan của tuần" chỉ có một ngày để xét;
nhánh 9 mở rộng chính hàm đó sang nhiều ngày.

### Sửa kế hoạch không chạm vào nhật ký

Mục kế hoạch là phần **dự kiến**; bữa **đã ghi** sống ở bảng của nhánh 8. Đổi một món đã ăn chỉ đổi
kế hoạch, con số "đã ăn" giữ nguyên món thực tế (bất biến 3). `MealPlanEditService` không có đường
nào ghi ra ngoài bốn bảng kế hoạch, và `MealPlanEditIntegrationTest` canh đúng điều đó bằng cách
đếm số dòng của **mọi** bảng trong schema `app` trước và sau một lượt đổi món — ngày
`app.diary_entries` ra đời, bài kiểm tự động canh luôn nó.

BR11 ("tạo lại không sửa mục đã ăn") có sẵn chỗ cắm: `MealPlanEditService.pinned()` là nơi duy nhất
quyết định mục nào lượt tạo lại không được chạm, hôm nay chỉ xét cờ khóa.

### Mã lỗi

| Tình huống | Mã | HTTP |
|---|---|---|
| Món vi phạm thành phần hoặc giới hạn bắt buộc | `RECIPE_NOT_ELIGIBLE` | 422 |
| `expected_plan_version` hoặc `expected_profile_version` cũ | `VERSION_CONFLICT` | 409 |
| Công thức đã đổi phiên bản | `VERSION_CONFLICT` | 409 |
| Số phần ≤ 0 hoặc ngoài miền, món khác vai trò, yêu cầu rỗng | `VALIDATION_ERROR` | 422 |
| Kế hoạch / mục không thuộc người gọi hoặc không tồn tại | `RESOURCE_NOT_FOUND` | 404 |
| Không còn gì để hoàn tác | `RESOURCE_NOT_FOUND` | 404 |

Món không hợp hồ sơ vẫn **xem được thông tin** ở `GET /api/v1/recipes`; chỉ nút Thêm bị chặn, và
thông điệp lỗi nói thẳng **thành phần nào** đang xung đột thay vì "không hợp lệ".

### Một chỗ duy nhất biến công thức thành ứng viên

`MealPlanCandidates` được tách ra khỏi `MealPlanService` ở nhánh này. Nếu đường "đổi món" chép lại
chuỗi *lọc → chấm điểm*, chỉ cần một bản quên một bước là món chứa thành phần dị ứng vào được thực
đơn qua nút Đổi món dù đường lập mới vẫn chặn nó. Bất biến 2 chỉ giữ được khi có **một** cửa.

---

## Danh sách mua sắm (nhánh 7)

Biến một thực đơn đã lưu thành danh sách đi chợ được. **Đều cần JWT**; chủ sở hữu suy từ
`jwt.getSubject()` và nằm trong chính câu truy vấn, nên thực đơn hay dòng của người khác trả 404.

| Endpoint | Việc |
|---|---|
| `GET /api/v1/meal-plans/{planId}/shopping-list` | tổng hợp nguyên liệu theo phạm vi ngày |
| `POST /api/v1/meal-plans/{planId}/shopping-list/items` | thêm một dòng tự nhập |
| `PATCH /api/v1/shopping-list-items/{itemId}` | đánh dấu đã có / đã mua, sửa dòng tự nhập |
| `DELETE /api/v1/shopping-list-items/{itemId}` | xóa một dòng **tự nhập** |

Tài liệu nhánh liệt kê hai endpoint; hai cái còn lại là hệ quả trực tiếp của yêu cầu "thêm dòng tự
nhập" trong cùng tài liệu — `PATCH` không tạo được dòng, và một dòng gõ nhầm mà không xóa được thì
tính năng đó chỉ dùng được một nửa.

Tham số truy vấn viết **snake_case** như các trường trong body: `start_date` ở query và
`start_date` trong phản hồi là cùng một thứ.

### Lượng cần là dẫn xuất, trạng thái là dữ liệu

Đây là quyết định chi phối cả nhánh:

| Phần | Nguồn | Khi thực đơn đổi |
|---|---|---|
| lượng cần, đơn vị, nhóm hàng, món sử dụng | hàm thuần của các mục kế hoạch | **tính lại** |
| đã có / đã mua / dòng tự nhập | người dùng khai | **giữ nguyên** |

Vì vậy đổi món **không reset danh sách**: dòng cũ ở nguyên chỗ cũ với dấu đã mua của nó, chỉ lượng
cần được viết lại (TC22). Và vì lượng cần dựng lại được từ kế hoạch, `shopping_list_version` chính
là `plan_version` — không có phiên bản thứ hai để lệch.

`GET` **có ghi dữ liệu**: mở màn hình mua sắm là lúc danh sách được đồng bộ với thực đơn. Phép đồng
bộ idempotent — chạy lại trên cùng một kế hoạch cho ra đúng cùng một trạng thái, không sinh dòng
thứ hai — nên với người dùng nó vẫn là "mở ra xem".

### Quy tắc gộp — phần dễ sai nhất

Khóa gộp là cặp **(mã nguyên liệu, trạng thái chế biến)**, không rộng hơn. Ba điều cấm của tài liệu
nhánh đều là hệ quả của đúng một câu đó:

1. **Không cộng 100 g gạo sống với 100 g cơm chín** — khác `food_state` là khác dòng, kể cả khi
   trùng mã nguyên liệu (TC10). "200 g" ở đây là con số không mua được ở quầy nào.
2. **Không tự đổi thìa sang gram** — mọi lượng nguyên liệu là gram vì công thức ghi gram; đầu vào
   của người dùng chỉ nhận đơn vị trong `MassUnits` (`g`, `gram`, `kg`).
3. **Không coi mọi loại cá là giống nhau** — khóa là *mã* nguyên liệu, không phải tên, không phải
   nhóm thực phẩm, không phải thẻ nguyên liệu chính.

Ràng buộc `uq_shopping_list_items_identity (list_id, ingredient_id, food_state)` ép điều này ở mức
database, để một lỗi lập trình ở lớp trên không đi qua được trong im lặng.

Lượng cần lấy từ `NutritionCalculator.ingredientsFor` chứ không nhân chia lại: nguyên liệu và dinh
dưỡng của cùng một mục phải cùng một tỉ lệ (bất biến 4, TC08).

### `GET …/shopping-list`

```
GET /api/v1/meal-plans/{planId}/shopping-list
    ?start_date=2026-10-14&end_date=2026-10-14&exclude_item_ids=7f4a…&exclude_item_ids=9c12…
```

| Tham số | Ý nghĩa |
|---|---|
| `start_date` / `end_date` | phạm vi ngày; bỏ trống = cả thực đơn. Ngoài phạm vi thực đơn → `VALIDATION_ERROR` |
| `exclude_item_ids` | mục kế hoạch **bỏ chọn** ở lần xem này ("món này tôi không cần mua") |

**Bỏ chọn là lựa chọn của lần xem, không phải thay đổi của thực đơn**: nó lọc các lần dùng rồi cộng
lại phần còn lại, và không xóa dòng nào. Mở lại không kèm tham số thì dòng cũ còn nguyên cùng dấu
đã mua của nó.

```json
{
  "data": {
    "list_id": "…", "plan_id": "…", "shopping_list_version": 5,
    "start_date": "2026-10-14", "end_date": "2026-10-14",
    "groups": [
      { "food_group": "GRAIN", "items": [
        { "item_id": "…", "ingredient_id": "ing.gao-te", "name": "Gạo tẻ",
          "food_state": "RAW", "food_group": "GRAIN", "state": "PURCHASED",
          "quantity": 150.00, "unit": "g", "purchased_quantity": 75.00,
          "remaining_quantity": 75.00, "estimated_cost_vnd": 3300.00,
          "used_by": [ { "plan_item_id": "…", "recipe_id": "rec.com-trang",
                         "name": "Cơm trắng", "quantity": 150.00 } ],
          "notes": [ { "code": "SHOPPING_NEED_MORE", "message": "Thực đơn đã đổi nên cần thêm 75 g…" } ] }
      ] },
      { "items": [ { "item_id": "…", "name": "Giấy ăn", "state": "NEEDED",
                     "custom_text": "Giấy ăn",
                     "notes": [ { "code": "SHOPPING_CUSTOM_LINE", "message": "…" } ] } ] }
    ],
    "estimated_cost_vnd": 48500.00, "estimated_remaining_cost_vnd": 21000.00,
    "cost_coverage": "PARTIAL",
    "notes": [ { "code": "SHOPPING_COST_INCOMPLETE", "message": "…" } ]
  }
}
```

Nhóm xếp theo thứ tự đi chợ — rau quả, thịt cá trứng, ngũ cốc, rồi các nhóm còn lại — chứ không
theo chữ cái. Nhóm **không có `food_group`** là các dòng tự nhập, luôn xếp cuối.

### `PATCH /api/v1/shopping-list-items/{itemId}`

```json
{ "state": "HAVE", "have_quantity": 0.5, "unit": "kg" }
```

| Trường | Ý nghĩa |
|---|---|
| `state` | `NEEDED` · `HAVE` (đã có ở nhà) · `PURCHASED` (đã mua) |
| `have_quantity` / `purchased_quantity` | người dùng khai; bỏ trống thì giữ nguyên |
| `unit` | đơn vị của hai con số trên **trong yêu cầu này**; bỏ trống = đơn vị của dòng |
| `custom_text` | chỉ dòng tự nhập; gửi lên dòng nguyên liệu → `VALIDATION_ERROR` |

- Tick `PURCHASED` **không kèm số** nghĩa là đã mua đủ phần còn cần — đó là điều vừa xảy ra ở chợ.
- Bỏ tick về `NEEDED` **xóa cả hai con số**: một dòng vừa nói "chưa mua gì" vừa mang "đã mua 200 g"
  là hai câu mâu thuẫn nằm cạnh nhau, và lần đọc sau không ai biết câu nào đúng.
- **Chỉ hỗ trợ đơn vị đã quy đổi được.** `"unit": "kg"` được nhận; `"unit": "thìa"` trả
  `VALIDATION_ERROR` kèm tên trường, **không** bị đoán thành một số gram. Một con số đoán trong
  danh sách đi chợ trông hệt như một con số đã biết.
- Không đòi `expected_plan_version`: đánh dấu đã mua không tranh chấp với một thao tác sửa thực
  đơn — hai người cùng nhà tick cùng một dòng thì kết quả là dòng đó đã mua, đúng ý cả hai.

Trả về **cả danh sách đã tính lại**, không riêng dòng vừa chạm: tick một dòng làm đổi tổng còn phải
mua, và để giao diện tự cộng lại tổng là tạo nguồn sự thật thứ hai cho con số tiền.

### Dòng tự nhập

```json
POST /api/v1/meal-plans/{planId}/shopping-list/items
{ "text": "Giấy ăn", "quantity": 2, "unit": "gói" }
```

Dòng tự nhập **không trỏ tới nguyên liệu nào**, nên nó không ảnh hưởng dinh dưỡng, không có giá,
không làm tổng chi phí thành "chưa đầy đủ" (nó vốn chưa bao giờ hứa có giá), và **không lượt đồng
bộ nào xóa nó** — nó không đến từ kế hoạch nên không kế hoạch nào lấy lại được.

Đơn vị của dòng tự nhập là chữ tự do ("gói", "bó", "chai"): nó không cộng với dòng nào nên không
cần quy đổi. Đổi lại, `PATCH` trên dòng đó chỉ nhận đúng đơn vị của chính nó.

`DELETE` chỉ xóa được dòng tự nhập. Dòng nguyên liệu là hệ quả của thực đơn và sẽ bị lượt đồng bộ
kế tiếp tạo lại — một nút xóa không xóa được gì là một lời hứa sai; cách bỏ nó khỏi danh sách là
`exclude_item_ids` hoặc đổi món.

### Giá — ước tính từ dữ liệu mẫu

Giá lấy từ `app.price_estimates` và **chỉ quy đổi được khi `base_unit` là đơn vị khối lượng thật**.
Nguyên liệu chưa có dòng giá, hoặc có giá theo "lít" / "quả", thì dòng đó:

- **không** mang `estimated_cost_vnd` (không phải 0 đồng — "chưa có giá" khác với "rẻ");
- mang ghi chú `SHOPPING_PRICE_UNAVAILABLE`;
- và cả danh sách mang `cost_coverage: "PARTIAL"` kèm `SHOPPING_COST_INCOMPLETE`.

`estimated_cost_vnd` của danh sách là tổng những dòng đã có giá; `estimated_remaining_cost_vnd` là
phần còn phải mua sau khi trừ lượng đã có và đã mua. Đây là **ước tính từ dữ liệu mẫu, không phải
báo giá cửa hàng** — MVP không kết nối cửa hàng, không thanh toán, không giao hàng.

### Nhắc đọc nhãn ở mức nguyên liệu

Lọc công thức theo dị ứng **không thay thế** việc đọc nhãn khi mua gia vị hoặc thực phẩm đóng gói
(bài học từ Mealime). Dòng nào là gia vị hoặc đồ chế biến sẵn **và** chưa được rà thành phần
(`allergen_status = UNKNOWN`) hoặc là nguyên liệu hỗn hợp (có nguyên liệu con) thì mang ghi chú
`SHOPPING_READ_PRODUCT_LABEL` — ví dụ nước mắm nấu từ cá cơm, hay gói gia vị nêm sẵn.

Chỉ **nhắc**, không chặn: món đã qua bước lọc rồi, đây là lời dặn lúc đứng ở quầy. Và chỉ nhắc ở
đúng hai nhóm đó — nhắc tràn lan sẽ làm người ta thôi đọc lời nhắc.

### Mã lý do (HTTP 200, không phải lỗi)

| Mã | Mức | Khi nào |
|---|---|---|
| `SHOPPING_NEED_MORE` | dòng | thực đơn đổi làm lượng cần vượt phần đã có / đã mua (TC22) |
| `SHOPPING_PRICE_UNAVAILABLE` | dòng | nguyên liệu chưa có giá quy đổi được |
| `SHOPPING_READ_PRODUCT_LABEL` | dòng | gia vị / đồ đóng gói: hãy đọc nhãn thành phần |
| `SHOPPING_ITEM_NO_LONGER_NEEDED` | dòng | dòng đã mua nhưng nguyên liệu không còn trong thực đơn |
| `SHOPPING_CUSTOM_LINE` | dòng | dòng tự nhập, nằm ngoài dinh dưỡng của thực đơn |
| `SHOPPING_COST_INCOMPLETE` | danh sách | tổng chi phí chỉ gồm những dòng đã có giá |

Nhánh này **không thêm mã lỗi nào**: mọi đường bị từ chối dùng lại `VALIDATION_ERROR` và
`RESOURCE_NOT_FOUND`.

### Bảng (V63)

| Bảng | Giữ gì |
|---|---|
| `app.shopping_lists` | một danh sách cho mỗi `(plan_id, start_date, end_date)`, kèm `plan_version` của lần đồng bộ gần nhất |
| `app.shopping_list_items` | dòng đi chợ: `ingredient_id` **nullable** (null = dòng tự nhập), `food_state`, `state`, `quantity`, `unit`, `have_quantity`, `purchased_quantity`, `custom_text` |

Tài liệu nhánh ghi một cột `date_range`; ở đây tách thành `start_date` / `end_date` vì `daterange`
chỉ có ở PostgreSQL còn bài kiểm migration chạy trên H2 chế độ PostgreSQL — và V60 đã tách đúng
cách này cho `meal_plans`, nên hai bảng so khớp phạm vi mà không phải đổi kiểu dữ liệu giữa đường.

Ba cột `food_state`, `food_group`, `display_name` là ảnh chụp lúc đồng bộ: `food_state` vì nó là
một nửa khóa gộp, hai cột kia để một dòng **đã mua** vẫn đọc được sau khi món dùng nguyên liệu đó
biến khỏi thực đơn — lúc ấy không còn snapshot nào trong kế hoạch để tra tên.

Xóa kế hoạch thì danh sách đi theo (`ON DELETE CASCADE`): một danh sách không còn thực đơn ở sau
lưng thì không tính lại được lượng cần, và một danh sách không tính lại được là một danh sách sai
ngay lần đổi món kế tiếp.

---

## Nhật ký và phản hồi (nhánh 8)

Ghi món **thực tế đã ăn**, tách hẳn khỏi thực đơn, và khép vòng lặp phản hồi về bước chấm điểm của
nhánh 4. **Đều cần JWT**; chủ sở hữu suy từ `jwt.getSubject()` và nằm trong chính câu truy vấn, nên
dòng của người khác trả 404.

| Endpoint | Việc |
|---|---|
| `GET /api/v1/diary?date=YYYY-MM-DD` | món đã ghi và **hai tổng tách biệt** của một ngày |
| `POST /api/v1/diary-entries` | ghi một món đã ăn, chống ghi trùng bằng `request_id` |
| `PATCH /api/v1/diary-entries/{entryId}` | sửa món hoặc số phần thực tế; trả tổng mới |
| `DELETE /api/v1/diary-entries/{entryId}` | xóa một dòng; tính lại tổng |
| `POST /api/v1/meal-feedback` | hợp khẩu vị, không thích, hoặc khó ăn tạm thời |

`date` **bắt buộc**: máy chủ không biết người dùng đang ở múi giờ nào, và đoán sai ngày sẽ hiện
nhầm cả một nhật ký. Ba endpoint ghi đều trả **cả ngày đã tính lại**, không riêng dòng vừa chạm —
để giao diện không phải tự cộng lại tổng.

### Hai tổng, không bao giờ một

```
meal_plan_items                  diary_entries
khẩu phần DỰ KIẾN      ──ghi──▶  khẩu phần ĐÃ XÁC NHẬN
không tự thành đã ăn             được phép khác kế hoạch
```

`planned_nutrition` và `logged_nutrition` là hai khối riêng trong cùng một response, mỗi chất kèm
`coverage` của chính nó. **Không có trường nào là tổng của hai khối đó**: cộng chúng lại sẽ đếm hai
lần đúng những bữa người dùng đã ăn theo kế hoạch.

Hệ quả, kiểm bằng test: ghi nửa phần của một mục kế hoạch một phần thì nhật ký tính một nửa còn kế
hoạch vẫn một phần (TC15); xóa một dòng làm tổng **đã ghi** đổi và để tổng **dự kiến** y nguyên
(TC17); hết ngày mà không ghi gì thì không dòng nhật ký nào được sinh ra, và các bữa chưa ghi không
tự thành đã ăn.

Chiều ngược lại cũng đóng: `DiaryService` không bao giờ ghi vào `meal_plan_items`, và
`MealPlanEditService` của nhánh 6 không bao giờ ghi vào `diary_entries`.

### `POST /api/v1/diary-entries`

**BR16: nhật ký được tạo sau xác nhận ngày, bữa và số phần.** Không trường nào có giá trị mặc định
phía máy chủ — giao diện điền sẵn từ kế hoạch, nhưng thứ tới được API phải là thứ người dùng đã bấm
xác nhận. Thiếu `servings` ở một món trong danh mục trả `VALIDATION_ERROR` kèm tên trường, chứ
không được hiểu thành một phần.

```json
{ "request_id": "4f1c…", "local_date": "2026-10-14", "meal_type": "LUNCH",
  "source_plan_item_id": "7f4a…", "servings": 0.5 }
```

"Ăn một nửa" chính là `"servings": 0.5`. `request_id` nằm **trong body**, unique cùng `user_id`:
gửi lại cùng giá trị trả về đúng bản ghi cũ, không tạo bữa thứ hai (TC16). Ghi trùng ở đây không
chỉ thừa một dòng — nó làm tổng đã ăn của ngày gấp đôi.

Hai dạng dòng, chọn đúng một:

| Dạng | Trường | Số liệu dinh dưỡng |
|---|---|---|
| Món trong danh mục | `recipe_id` và/hoặc `source_plan_item_id`, kèm `servings` | từ snapshot chụp lúc ghi |
| Món ngoài bộ dữ liệu | `custom_text`, kèm `amount_text` tùy chọn | **không có** |

`source_plan_item_id` là **nguồn tùy chọn** — nhật ký ghi được món ngoài thực đơn. Gửi kèm một
`recipe_id` **khác** món của mục kế hoạch là hợp lệ và có chủ đích: đó là lối "nguyên liệu đổi đáng
kể → chọn biến thể có sẵn", và lúc đó số liệu lấy theo biến thể thật sự đã ăn, không giữ số của món
gốc.

`estimate_incomplete: true` là người dùng tự đánh dấu "nguyên liệu đã đổi đáng kể, đây chỉ là ước
tính". Số vẫn tính theo công thức gốc vì không có nguồn nào khác, nhưng tổng của ngày hạ xuống
`PARTIAL` và nói ra lý do.

**Món ngoài bộ dữ liệu không sinh ra vi chất từ tên món.** Dòng đó không có snapshot nên không đóng
góp con số nào vào tổng — điều này được ép bằng `ck_diary_entries_shape` ở mức database, không phải
bằng một câu `if` ai đó có thể quên.

`PATCH` sửa trong **đúng dạng của dòng**: một dòng món danh mục đổi được món và số phần, một dòng
tự ghi đổi được chữ. Đổi hẳn dạng thì xóa rồi ghi lại — một thao tác lặng lẽ biến một dòng không có
số liệu thành một dòng có số liệu là thứ khó nhìn ra nhất khi tổng của ngày đổi.

### `GET /api/v1/diary`

```json
{
  "data": {
    "local_date": "2026-10-14",
    "entries": [
      { "entry_id": "…", "local_date": "2026-10-14", "meal_type": "LUNCH",
        "recipe_id": "rec.com-trang", "name": "Cơm trắng", "recipe_version": 1,
        "servings": 0.50, "estimate_incomplete": false, "source_plan_item_id": "7f4a…",
        "nutrition": { "ENERGY": { "known_value": 135.0, "unit": "kcal",
                                   "coverage": "COMPLETE", "missing_ingredient_ids": [] } },
        "coverage": "PARTIAL", "created_at": "2026-10-14T12:30:00+07:00", "notes": [] },
      { "entry_id": "…", "meal_type": "SNACK", "custom_text": "Bánh mì cô Ba",
        "amount_text": "một ổ", "estimate_incomplete": false, "coverage": "PARTIAL",
        "notes": [ { "code": "DIARY_OFF_CATALOG_ENTRY", "message": "…" } ] }
    ],
    "planned_nutrition": { "ENERGY": { "known_value": 820.0, "unit": "kcal",
                                       "coverage": "PARTIAL", "missing_ingredient_ids": [] } },
    "planned_coverage": "PARTIAL",
    "logged_nutrition":  { "ENERGY": { "known_value": 135.0, "unit": "kcal",
                                       "coverage": "COMPLETE", "missing_ingredient_ids": [] } },
    "logged_coverage": "PARTIAL",
    "remaining": {
      "energy_target_kcal": 2180.0,
      "by_nutrient": { "ENERGY": { "known_value": null, "unit": "kcal",
                                   "coverage": "PARTIAL", "missing_ingredient_ids": [] } },
      "notes": [ { "code": "DIARY_REMAINING_ESTIMATE", "message": "…" } ]
    },
    "method_notes": [],
    "notes": [ { "code": "DIARY_LOGGED_TOTAL_ONLY", "message": "Tổng của các món bạn đã ghi…" } ]
  }
}
```

Khối dinh dưỡng dùng lại **đúng hình dạng đã công bố ở nhánh 2**, nên giao diện đọc tổng kế hoạch
và tổng nhật ký bằng một đoạn mã, và `known_value` null vẫn **xuất hiện** ở cả hai.

`DIARY_LOGGED_TOTAL_ONLY` có mặt ở **mọi** lượt đọc, kể cả khi đã ghi đủ ba bữa: con số đó là "tổng
của các món đã ghi", không phải "tổng lượng ăn cả ngày". Hệ thống chỉ biết những gì người dùng bấm
vào — gắn nhãn kia lên nó là một lời khẳng định không có dữ liệu nào đỡ, và nó rất dễ tin vì con số
trông đầy đủ.

`logged_coverage` xuống `PARTIAL` **ngay khi** có một món ngoài bộ dữ liệu hoặc một dòng đánh dấu
ước tính, kể cả lúc mọi chất đều có số: lúc đó cái thiếu không phải một ô trong bảng mà là cả một
bữa.

### Phần còn lại của ngày (UC08)

`remaining` là **dữ liệu cho khu vực gợi ý sau khi đã ăn**, không phải một đơn thuốc. Ba giới hạn
được ép trong chính phép tính:

1. **Chất chưa đủ dữ liệu thì không có con số** — `known_value` null kèm `PARTIAL`. Tổng đã ghi của
   chất đó còn thiếu nghĩa là phần đã ăn đang bị tính hụt, nên khoảng cách sẽ lớn hơn thực tế, và
   một con số lớn hơn thực tế ở đây mời người ta ăn bù thứ họ đã ăn rồi. Xét **từng chất một**:
   danh mục chưa có số iốt không phải lý do để giấu đi khoảng cách năng lượng đã tính được đủ.
2. **Có món ngoài bộ dữ liệu hoặc dòng ước tính thì không chất nào có số** — thiếu ở mức cả tập đã
   ghi thổi phồng khoảng cách của mọi chất cùng lúc.
3. **Nhật ký quá thiếu dữ liệu thì không nói gì về khẩu phần cả ngày** — chỉ còn
   `DIARY_TOO_SPARSE`, và khu vực gợi ý quay về gợi ý đa dạng thông thường.

Khối này **không gọi bộ gợi ý và không đề xuất món nào**: nó trả về khoảng cách, còn việc chọn món
vẫn đi qua `POST /api/v1/recommendations` của nhánh 4 — đường duy nhất có bước lọc dị ứng. Và không
có nhánh nào sinh ra lời khuyên tăng liều bổ sung.

### `POST /api/v1/meal-feedback`

```json
{ "recipe_id": "rec.com-trang", "type": "HARD_NOW" }
```

| `type` | `expires_at` | Điểm P |
|---|---|---|
| `TASTY` | `null` | +10 |
| `DISLIKE` | `null` | −25 |
| `HARD_NOW` | `created_at + 7 ngày` | −20 |

Chỉ cái **tạm thời** mới hết hạn (QĐ-03): "khó ăn lúc này" là một trạng thái của thai kỳ, còn "ngon"
và "không thích" là ý kiến. Lựa chọn chủ động vĩnh viễn (`FAVORITE`, `BLOCKED`) nằm ở
`app.user_recipe_preferences` của nhánh 3 và **không có** `expires_at`.

**Không lựa chọn nào ở đây trở thành dị ứng.** Chúng cộng trừ điểm khẩu vị; món vẫn qua đúng bộ lọc
an toàn như mọi món khác và vẫn xuất hiện trong gợi ý nếu nó thật sự hợp. Muốn chặn hẳn một món thì
đó là `BLOCKED` — một lựa chọn chủ động, không hạn, và nó loại món khỏi ứng viên chứ không trừ điểm.

Bảng ghi theo kiểu **nhật ký**: mỗi lần chạm là một dòng mới, không đè lên dòng cũ. Lúc chấm điểm
chỉ lấy **một** phản hồi gần nhất còn hiệu lực, và hạn được lọc ngay trong câu truy vấn — một
`HARD_NOW` hết hạn thôi ảnh hưởng xếp hạng **mà không cần** job dọn dẹp nào chạy đúng giờ. Dòng cũ
vẫn nằm trong bảng: nó là lịch sử đã xảy ra.

### Phản hồi vào điểm P ở đâu

**Cả ba đường chấm điểm**: gợi ý món (nhánh 4), lập thực đơn (nhánh 5) và đổi món / tạo lại
(nhánh 6). Công thức đã có sẵn ở `RecipeRanker` từ trước; việc của nhánh 8 là cắm nguồn dữ liệu và
bảo đảm không đường nào bị bỏ sót.

`MealFeedbackSignal` chuyển từ gói `service` sang `domain` để vừa là giá trị cột `type` vừa là tín
hiệu chấm điểm, tránh hai danh sách cùng nghĩa khác tên với hai bộ số sẽ lệch nhau ngay lần chỉnh
đầu tiên.

Tín hiệu sống ở **`RankingInputs.feedbackByRecipe`**, cạnh `favoriteRecipeIds`, chứ không đi kèm
từng `RecipeRanker.Scorable`. Đó là điều kiện để lời hứa trên đứng vững: `RankingInputs` là vật thể
**duy nhất đi qua cả hai đường chấm điểm của một lượt lập** — chọn ứng viên cho từng vị trí
(`MealPlanCandidates`) và chấm lại điểm P trên từng phương án đang dựng (`MealPlanGenerator`). Nếu
tín hiệu đi kèm từng món, một trong hai đường sẽ quên truyền nó và cùng một món nhận hai thứ hạng
khác nhau trong cùng một lượt.

Nạp **một lượt cho mỗi request**, ở `limitsOf(...)` của hai service thực đơn, rồi đi cùng
`PlanLimits` xuống mọi bước. Tra lại trong vòng lặp chọn ứng viên sẽ là một câu truy vấn cho mỗi
vai trò của mỗi bữa — với thực đơn bảy ngày của nhánh 9 thì là hàng chục câu giống hệt nhau. Để lấy
được `userId` ở đó, `ProfileSnapshot` nay mang thêm trường ấy: `profileId` là mã thai kỳ, còn phản
hồi sau bữa thuộc về **người dùng**.

Hành vi, kiểm bằng test: bấm "không thích" rồi lập lại thực đơn cùng seed thì món đó **bị thay**, và
nó cũng **không còn đứng đầu** danh sách Đổi món — nhưng vẫn nằm trong danh sách, và vẫn vào được
thực đơn khi nó là lựa chọn duy nhất của vai trò đó. Phản hồi khẩu vị hạ điểm; nó không bao giờ là
một bộ lọc (bất biến 5).

### Mã lý do (HTTP 200, không phải lỗi)

| Mã | Mức | Khi nào |
|---|---|---|
| `DIARY_LOGGED_TOTAL_ONLY` | ngày | **luôn luôn**: con số đã ghi là tổng của các món đã ghi |
| `DIARY_PLANNED_MEAL_NOT_LOGGED` | ngày | kế hoạch còn bữa chưa ghi; chúng không nằm trong tổng đã ghi |
| `DIARY_OFF_CATALOG_ENTRY` | ngày · dòng | món ngoài bộ dữ liệu: chỉ có tên và lượng |
| `DIARY_ESTIMATE_INCOMPLETE` | ngày · dòng | người dùng đánh dấu nguyên liệu đã đổi đáng kể |
| `DIARY_REMAINING_ESTIMATE` | phần còn lại | khoảng cách là ước tính để gợi ý, không phải lượng cần bù |
| `DIARY_TOO_SPARSE` | phần còn lại | chưa đủ dữ liệu để nói về khẩu phần cả ngày |

Nhánh này **không thêm mã lỗi nào**: mọi đường bị từ chối dùng lại `VALIDATION_ERROR` và
`RESOURCE_NOT_FOUND`.

### Bảng (V64)

| Bảng | Giữ gì |
|---|---|
| `app.diary_entries` | `local_date`, `meal_type`, `recipe_id` **nullable**, `recipe_version`, `recipe_snapshot`, `actual_servings`, `custom_text`, `amount_text`, `estimate_incomplete`, `source_plan_item_id` **nullable**, `request_id` |
| `app.meal_feedbacks` | `recipe_id`, `type`, `created_at`, `expires_at` **nullable** |

Index đúng hai cột nhánh yêu cầu: `(user_id, local_date)` — "nhật ký hôm nay của tôi" là lối vào
duy nhất của màn hình, và cũng là đường kiểm tra chủ sở hữu.

Ba ràng buộc là chỗ database chặn một lỗi của lớp trên:

| Ràng buộc | Chặn gì |
|---|---|
| `uq_diary_entries_request` | ghi trùng — không chỉ thừa một dòng, nó làm tổng đã ăn gấp đôi (TC16) |
| `ck_diary_entries_shape` | một dòng vừa trỏ tới công thức vừa mang tên tự do, mập mờ đúng ở chỗ nó có số liệu hay không |
| `ck_meal_feedbacks_expiry` | một `DISLIKE` mang hạn: nó sẽ tự biến mất sau bảy ngày mà không ai hiểu vì sao món đó quay lại |

`source_plan_item_id` dùng `ON DELETE SET NULL`, **không** CASCADE: xóa kế hoạch là bỏ phần dự
kiến, không phải phủ nhận bữa đã ăn. CASCADE ở đây sẽ là bất biến 3 bị phá ngay ở tầng dữ liệu —
sửa thực đơn sẽ lặng lẽ viết lại nhật ký, và tổng đã ăn của một ngày trong quá khứ đổi mà không ai
chạm vào nó.

Số **V64** lấy theo `_CONTRACT.md` mục 2: database dùng chung ở V62 lúc bắt đầu nhánh, còn repo đã
có V63 (nhánh 7) chưa chạy lên đó, nên số kế tiếp phải trống ở cả hai phía. **Chạy V63 trước V64**:
`out-of-order` đang tắt, nên áp V64 lên database trước sẽ làm Flyway im lặng bỏ qua V63 và các bảng
mua sắm không bao giờ được tạo.

---

## Thực đơn ba và bảy ngày (nhánh 9)

Không có endpoint mới và **không có migration**: `app.meal_plans` đã tách `start_date` và
`end_date` từ V60, còn `meal_slots.local_date` đã là một phần của khóa duy nhất. Nhánh này chỉ mở
`day_count` và thêm hai thứ chỉ tồn tại khi kế hoạch dài hơn một ngày.

| Trường | Giá trị |
|---|---|
| `day_count` | **1, 3 hoặc 7**, mặc định 1 |

Giá trị khác bị từ chối tại trường (`VALIDATION_ERROR`, `error.fields.day_count`) — kể cả 5, dù nó
nằm giữa 3 và 7: đây là một lựa chọn sản phẩm (một ngày, ba ngày, một tuần), không phải một khoảng.

`days[]` trả về **đúng `day_count` phần tử**, ngày liên tiếp từ `start_date`, mỗi ngày có tổng dinh
dưỡng, chi phí và ghi chú của riêng nó. Hình dạng mỗi ngày **không đổi** so với nhánh 5.

### Một cấu trúc, nhiều lượt ghép

Cấu trúc bữa và danh sách ứng viên không phụ thuộc ngày — cùng hồ sơ, cùng danh mục, cùng giới hạn —
nên bước tạo cấu trúc và bước lọc chạy **một lượt** rồi dùng lại cho mọi ngày; chỉ bước ghép lặp
theo ngày. Mỗi ngày vẫn được chấm theo **mục tiêu dinh dưỡng của riêng nó**: gộp bảy ngày vào một
hàm mục tiêu sẽ cho phép một ngày thiếu được bù bằng một ngày thừa, mà người ta thì ăn theo ngày.

Một ngày không ghép được là **cả kế hoạch** `NO_MATCH`: sáu ngày rưỡi không phải thứ người dùng yêu
cầu, và lưu nó lại sẽ tạo ra một tuần thiếu ngày mà giao diện vẫn mở ra được.

`locked_items` không mang ngày (hợp đồng nhánh 5 giữ nguyên), nên một món khóa áp cho bữa đó ở
**mọi ngày** — đúng nghĩa "người dùng chủ động lập lịch lặp". Món đó không bị tính vào đa dạng và
không sinh ghi chú lặp.

### Đa dạng liên ngày

Ba thành phần liên ngày của `V` có sẵn từ nhánh 4; nhánh này cho chúng dữ liệu:

```
V = max(0, 100 − 40·I_recipe − 20·I_mainIngredient − 10·n_week)
```

| Thành phần | Nguồn |
|---|---|
| `I_recipe` | món đã có trong **hai ngày gần đây** của chính kế hoạch |
| `I_mainIngredient` | nguyên liệu chính của **ngày hôm trước** |
| `n_week` | số lần món đã có trong **tuần đang tạo**, không tính lần đang xét |

Cập nhật sau **từng ngày**, và trong một ngày thì sau **từng lựa chọn** — một bảng điểm tính sẵn
dùng chung cho bảy ngày sẽ cho ra bảy bản sao của cùng một ngày tốt nhất.

### Giới hạn thực phẩm theo tuần

Điều kiện cứng duy nhất của module **không** đánh giá được trong phạm vi một ngày. Khai trong
`nutritionRules.json`:

```json
"weeklyFoodLimits": [
  { "code": "SEAFOOD_BEST_CHOICE", "label": "cá và hải sản nhóm nên ăn",
    "ingredientIds": ["ing.ca-hoi", "ing.ca-com", "ing.tom-su", "ing.tom-kho"],
    "maxGramPerWeek": 340, "sourceId": "src.fda-epa-fish-advice" }
]
```

Bốn điều quyết định cách nó hoạt động:

1. **Theo nhóm dữ liệu, không theo tên món.** Bộ sinh cộng **gram nguyên liệu** của cả kế hoạch,
   nên đổi tên món, đổi khẩu phần hay chia một món thành hai bữa đều không đi vòng được. Tên món
   không tham gia phép so sánh nào.
2. **Mỗi nhóm một dòng, mỗi dòng một mức.** FDA/EPA phân loại cá theo mức thuỷ ngân và mỗi nhóm có
   tần suất riêng; gộp lại là coi mọi loại cá như nhau. Thêm nhóm cá nhiều thuỷ ngân là thêm một
   dòng, không phải nới mức của dòng đang có. Không dùng `FoodGroup` vì `MEAT_FISH_EGG` gộp cả
   thịt — cùng lý do với `DIET_FILTER_COARSE`.
3. **Kiểm trên tổng cả kế hoạch**, không trên từng ngày: mức đã dùng của các ngày trước đi cùng
   sang ngày sau, và một món chỉ bị loại khi *tổng* vượt mức. Mức là mức của một tuần và áp cho cả
   kế hoạch kể cả khi kế hoạch ngắn hơn bảy ngày.
4. **Chỉ nguyên liệu khai thẳng trong công thức.** Danh mục không khai gram cho nguyên liệu con,
   nên không có cách nào biết một muỗng nước mắm mang bao nhiêu gram cá cơm — đoán một con số ở đây
   sẽ làm điều kiện an toàn chạy trên số bịa. Muốn tính thì khai chính nguyên liệu dẫn xuất thành
   một dòng. (Khác với dị ứng: ở đó câu hỏi là *có hay không*, và bước lọc vẫn duyệt đệ quy.)

Một mã nguyên liệu gõ sai trong `weeklyFoodLimits` **không có triệu chứng** — nhóm vẫn tồn tại, chỉ
là không khớp gì — nên `NutritionSeedValidator` chặn nó ở bước kiểm seed và ứng dụng không khởi
động.

Ba đường vào kế hoạch đều kiểm giới hạn này: lập mới, `POST …/regenerate`, và `PATCH …/items/{id}`
(vượt mức → `RECIPE_NOT_ELIGIBLE` 422 kèm con số). Một điều kiện cứng chỉ là điều kiện cứng khi
không đường nào bỏ qua nó.

### Mã lý do thêm ở nhánh này (HTTP 200)

| Mã | Khi nào |
|---|---|
| `WEEKLY_FOOD_LIMIT_CHECKED` | nhóm có mặt trong kế hoạch, kèm `đã dùng/mức` — **không** hạ `status`, vì đây là kết quả tốt |
| `WEEKLY_FOOD_LIMIT_REACHED` | nhóm đã dùng hết mức, phần còn lại của kế hoạch không nhận thêm |
| `REPEATED_RECIPE_ACROSS_DAYS` | cùng món trong hai ngày gần nhau, **trừ** mục người dùng chủ động giữ |

`PARTIAL_DAY_PLAN` xuất hiện ở `days[].notes` của **từng ngày** (TC23), và một lần ở mức kế hoạch.
Ghi chú mức kế hoạch gom theo mã; con số của từng ngày nằm ở ngày đó.

### Hiệu năng

| Tham số | Giá trị |
|---|---|
| Hạn thời gian bảy ngày | **5 giây** cho cả kế hoạch, không phải mỗi ngày |
| Ứng viên mỗi vị trí | 15, mở lên 30 khi thiếu phương án |
| Beam | 20 |

**Số đo thực tế** (Windows 11, JDK 21, H2 profile test, 19/10/2026) — `POST /meal-plans/preview`
trên seed thật, đo cả HTTP và JPA, lượt gọi **đầu tiên** của JVM:

| Lượt | Đo được | Mục tiêu |
|---|---|---|
| 1 ngày, 4 bữa | **859 ms** | 2 giây |
| 7 ngày, 4 bữa | **1 520 ms** | 5 giây |
| 7 ngày, 4 bữa (JVM đã ấm) | **350 ms** | 5 giây |

Riêng vòng beam, trên cấu trúc nặng nhất API nhận (6 bữa, 11 vị trí, 30 ứng viên và 4 mức khẩu phần
mỗi vị trí, 7 ngày): **1 411 ms**. Không phải chỉnh beam hay seed để đạt hạn.

Hết thời gian mà chưa đủ số ngày → `NO_MATCH` kèm `SEARCH_LIMIT`; **không** kết luận không tồn tại
thực đơn phù hợp (TC19).

### Chặn theo gói — chỗ duy nhất của cả module (QĐ-04)

MVP **không gate gì**. Khi quyết định bán, chỗ cắm là lượt lập có `day_count != 1`, trong
`MealPlanService.preview`, theo đúng mẫu `CalendarReminderService`:

```java
entitlements.requireFeature(userId, PlanFeature.PERSONALISED_GUIDANCE);
```

Dùng lại key đã có, **không thêm giá trị mới vào `PlanFeature`** — enum đó khớp cứng thứ tự với
`pricing-catalog.ts` bên FE. Lỗi trả `PLAN_UPGRADE_REQUIRED` (403), đã có sẵn. **Không bao giờ**
gate lọc dị ứng, cảnh báo thành phần, điều kiện chế biến hay giới hạn theo tuần: đó là an toàn,
không phải quyền lợi bán.

### Hiển thị (phối hợp với FE)

- Điện thoại: **thanh chọn ngày theo chiều ngang**. Máy tính: **bảng tuần**.
- **Không ép một bảng bảy cột vào màn hình nhỏ.**
- Ngày có ghi chú hoặc dữ liệu thiếu: đánh dấu bằng **văn bản**, không chỉ khác màu — `days[].notes`
  và `days[].nutrition.*.coverage` có sẵn cho đúng việc này.

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
- Nhánh 2 thêm `retentionFactors` và `energyEstimate` vào `nutritionRules.json`
  (`nutrition-seed-v2`); nhánh 9 thêm `weeklyFoodLimits` và nguồn `src.fda-epa-fish-advice`, nên
  `rules_version` hiện là **`nutrition-seed-v3`**. Ba khối này **không vào database**:
  `NutritionRulesProvider` đọc thẳng từ classpath, và thiếu hệ số thì ứng dụng không khởi động.
- Tăng `rules_version` làm **mọi bản xem trước đang mở hết hiệu lực** (`VERSION_CONFLICT` lúc lưu)
  và đánh dấu kế hoạch cũ sinh bằng tập quy tắc khác — đúng như thiết kế: con số đã trình bày cho
  người dùng phải tái lập được bằng chính tập quy tắc đã sinh ra nó.

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
**HTTP 200**, vì không tìm được thực đơn là kết quả nghiệp vụ hợp lệ. Sáu mã `SHOPPING_*` của
nhánh 7 và sáu mã `DIARY_*` của nhánh 8 cũng vậy: chúng nói về một dòng, một danh sách hay một
ngày, không phải về một yêu cầu thất bại.
