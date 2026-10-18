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
| 6–10 | chưa |

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
    "energy_estimate": { "kcal_per_day": null, "rules_version": "nutrition-seed-v2",
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
    "rules_version": "nutrition-seed-v2",
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

**Nhánh này chỉ lập một ngày.** `day_count` khác 1 bị từ chối tại trường; ba và bảy ngày thuộc
nhánh 9.

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
    "rules_version": "nutrition-seed-v2",
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
| Hạn thời gian một ngày | 2 giây |

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
HTTP và JPA, mất **648 ms**. Cả hai nằm trong mục tiêu 2 giây.

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
