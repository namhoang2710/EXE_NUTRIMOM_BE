# Chốt quyết định — Module dinh dưỡng mẹ bầu

Nguồn: `Dac_ta_tinh_nang_dinh_duong_me_bau_v2.pdf` (v2.0, 14/10/2026).
Tài liệu này chốt 6 điểm đặc tả bỏ ngỏ. Mọi quyết định dưới đây đã đối chiếu với code thật
trong `Backend/EXE_NUTRIMOM_BE` tại thời điểm 16/10/2026 (Flyway max = V41).

---

## Hai điều chỉnh so với đề xuất ban đầu

Sau khi đọc code, hai gợi ý trước bị bác và thay bằng phương án khác:

| Gợi ý cũ | Vì sao bỏ | Thay bằng |
|---|---|---|
| "Khai trọn bộ 4 ErrorCode nutrition ở nhánh 1" | `VERSION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `VALIDATION_ERROR`, `PLAN_UPGRADE_REQUIRED` **đã có sẵn**. `NO_MATCH` và `SEARCH_LIMIT` là kết quả nghiệp vụ trả HTTP 200 trong body, không phải mã lỗi. | Chỉ thêm **đúng 1 mã mới**: `RECIPE_NOT_ELIGIBLE` (422) |
| "Khai feature key gói sớm ở nhánh 1 cho rẻ" | `PlanFeature` là enum 10 giá trị, javadoc cảnh báo **thứ tự khớp cứng** với `pricing-catalog.ts` bên FE; thêm một dòng là dán sai nhãn cả bảng giá và mọi gói phải khai lại | Không thêm key nào. Dùng `PERSONALISED_GUIDANCE` đã có, khi nào thật sự cần chặn |

---

## QĐ-01 — Bản xem trước lưu ở bảng, không ở cache

**Chốt:** bảng `meal_plan_preview`.

| Cột | Ghi chú |
|---|---|
| `id` | khóa chính; định danh trả ra dạng `pv_...` theo ví dụ JSON mục 14 |
| `user_id` | FK |
| `request_id` | **unique cùng `user_id`** |
| `profile_version`, `rules_version` | kiểm tra lại lúc lưu |
| `status` | READY / READY_WITH_NOTES / NO_MATCH |
| `payload` | JSONB — nguyên văn body xem trước |
| `created_at`, `expires_at` | TTL **30 phút** (mục 15) |

- `POST /meal-plans` đọc preview theo id: hết hạn hoặc `profileVersion` lệch thì **không lưu**.
- Unique `(user_id, request_id)` chính là cách xử lý "gửi hai lần vì mạng chậm" ở mục 15 — lần
  thứ hai trả lại đúng bản ghi cũ, không sinh bản thứ hai.
- Dọn dẹp: job định kỳ mỗi giờ xóa bản hết hạn. Nếu chưa muốn thêm scheduler, xóa lười lúc đọc
  cũng đủ cho phạm vi đồ án.

**Lý do không dùng cache:** sống qua restart, mở DB ra xem được lúc demo, không thêm hạ tầng.
Repo hiện chỉ có `InMemoryRateLimiterStore`, không có Redis.

**Thuộc nhánh:** `feature/meal-plan-generation` — migration V45.

---

## QĐ-02 — `MealSlot` có cột `sequence`

Mục 13 nói "bữa phụ dùng sequence" nhưng danh sách trường không có cột này. Bổ sung:

- `sequence SMALLINT NOT NULL DEFAULT 1`
- Unique đổi thành bộ bốn: `(plan_id, local_date, meal_type, sequence)`
- `BREAKFAST` / `LUNCH` / `DINNER`: luôn `sequence = 1`; tạo slot thứ hai cùng loại → `VALIDATION_ERROR`
- `SNACK`: `sequence` từ 1 đến 3

**Hợp đồng API giữ nguyên hình dạng cũ.** Vẫn truyền `mealTypes: []` đúng như ví dụ JSON đã in
trong tài liệu, thêm một trường tùy chọn `snackCount` (1–3, mặc định 1). Không đổi sang mảng
object `{mealType, sequence}` — ví dụ JSON là hợp đồng đã công bố trong tài liệu bảo vệ, đổi
hình dạng sẽ làm tài liệu và code lệch nhau.

`roleTargetShare` của bữa phụ (15% theo mục 10) chia đều cho `snackCount`.

**Thuộc nhánh:** `feature/meal-plan-generation` — migration V45.

---

## QĐ-03 — Tách vai trò hai bảng sở thích, bỏ `expiresAt` trùng

Đặc tả cho cả `UserRecipePreference` và `MealFeedback` đều có `expiresAt`, cả hai cùng ảnh hưởng
điểm `P`. Tách dứt khoát:

### `UserRecipePreference` — lựa chọn chủ động, KHÔNG có hạn

- **Bỏ cột `expiresAt`.**
- `preference_type`: `FAVORITE` | `BLOCKED`
- Unique `(user_id, recipe_id)`
- `BLOCKED` = nút "Không muốn gợi ý món này". Sống đến khi người dùng tự đổi.

### `MealFeedback` — phản hồi sau bữa ăn, CÓ hạn

- Giữ `expires_at`.
- `type`: `TASTY` | `DISLIKE` | `HARD_NOW`
- `HARD_NOW` → `expires_at = now + 7 ngày`
- `TASTY`, `DISLIKE` → `expires_at = null`

**Lý do chỉ `HARD_NOW` có hạn:** mục 11 gắn "bảy ngày mặc định" đúng vào "Khó ăn lúc này", và
mục 15 nói "thay khẩu vị tạm thời do khó ăn → giảm ưu tiên **có thời hạn**". Chỉ cái tạm thời
mới hết hạn.

### Ranker đọc như sau

| Nguồn | Tác động lên `P` |
|---|---|
| `BLOCKED` | **loại khỏi ứng viên**, không phải trừ điểm (mục 11 nói rõ) |
| `FAVORITE` | +20 |
| `MealFeedback` gần nhất còn hiệu lực | `TASTY` +10 · `DISLIKE` −25 · `HARD_NOW` −20 |

Chỉ áp **một** phản hồi gần nhất. Các sở thích khác (nhóm ẩm thực, tags dung nạp, nguyên liệu
không thích) vẫn cộng trừ riêng.

**Thuộc nhánh:** `feature/nutrition-profile` (V43) định nghĩa `UserRecipePreference` không có
cột `expiresAt` ngay từ đầu · `feature/nutrition-diary` (V47) làm `MealFeedback`.

---

## QĐ-04 — Không chặn theo gói trong MVP, và không thêm `PlanFeature` mới

**Bằng chứng:** `entitlement/domain/PlanFeature.java` là enum đúng 10 giá trị, javadoc ghi rõ
danh sách và **thứ tự** khớp bảng quyền lợi `pricing-catalog.ts` bên FE — FE lấy nhãn từ gói đầu
rồi index các gói còn lại bằng cùng chỉ số, nên thêm/bớt/đảo một dòng là dán sai nhãn cho cả
cột khác, và mọi gói phải khai đủ.

**Chốt:**

- MVP **mở toàn bộ** hành trình dinh dưỡng cho mọi gói. Không gate gì.
- Khi nào thật sự muốn bán, gắn **thực đơn 3 và 7 ngày** vào `PERSONALISED_GUIDANCE` đã có sẵn:
  `entitlements.requireFeature(userId, PlanFeature.PERSONALISED_GUIDANCE)` theo đúng mẫu
  `CalendarReminderService` đang dùng. Lỗi trả `PLAN_UPGRADE_REQUIRED` (403) đã có.
  Việc này thuộc nhánh `feature/meal-plan-multiday`, không phải nhánh 1.
- **Không bao giờ** chặn theo gói: lọc dị ứng, cảnh báo thành phần, điều kiện chế biến. Đó là an
  toàn, không phải quyền lợi bán.

---

## QĐ-05 — Ảnh món đi đường public media đã có

Repo tách sẵn hai đường và `FileStorageConfig` còn chủ động ném lỗi nếu hai bucket trùng nhau:

| Đường | Service | Dùng cho |
|---|---|---|
| Riêng tư | `PrivateR2StorageService` | hồ sơ y tế |
| Công khai | `knowledge/service/R2StorageService`, bucket `app.knowledge.r2.bucket-name` | ảnh bài viết, avatar chuyên gia |

**Chốt:** ảnh món dùng đường **công khai**, cùng chỗ với ảnh bài viết.

- `Recipe.image_key VARCHAR(255) NULL` — lưu **object key**, không lưu URL đầy đủ. DTO dựng
  `imageUrl` lúc trả về, đổi domain không phải migrate dữ liệu.
- Prefix `recipes/`.
- **Không dùng presigned URL**: `GET /recipes` là endpoint công khai, presigned sẽ hết hạn và
  phá cache ảnh.
- **Không có endpoint upload ảnh** — không có màn quản trị. Ảnh đẩy lên bằng script seed.

**Thuộc nhánh:** `feature/nutrition-catalog-seed` — V42.

---

## QĐ-06 — permitAll đúng hai path, và mọi đường dẫn phải thêm `/api/v1`

**Sửa đường dẫn:** đặc tả viết `GET /recipes`, nhưng repo dùng prefix `/api/v1`. Toàn bộ 18
endpoint trong mục 14 phải thêm prefix khi hiện thực.

```java
.requestMatchers(HttpMethod.GET, "/api/v1/recipes", "/api/v1/recipes/*").permitAll()
```

Đúng một dòng, có `HttpMethod.GET`, theo mẫu `/api/v1/knowledge/articles` và `/api/v1/experts`
đang dùng. **Không** permitAll cả prefix.

Giữ `authenticated` toàn bộ: `/api/v1/me/nutrition-profile`, `/api/v1/recommendations`,
`/api/v1/meal-plans/**`, `/api/v1/diary**`, `/api/v1/diary-entries/**`,
`/api/v1/shopping-list-items/**`, `/api/v1/meal-feedback`.

`POST /api/v1/recommendations` **không** công khai — nó cần hồ sơ người dùng.

**Rate limit:** thêm policy `recipes` vào `policies:` trong `application.yml`, khóa theo IP.
`RateLimitFilter` khớp path bằng `AntPathMatcher` theo tên policy, không có annotation.

**Thuộc nhánh:** `feature/nutrition-catalog-seed` — V42.

---

## Phụ lục — ErrorCode: chỉ thêm đúng một mã

| Tình huống trong đặc tả | Dùng gì |
|---|---|
| 400 INPUT_INVALID | `VALIDATION_ERROR` (422) — đã có |
| 401 AUTH_REQUIRED | đã có |
| 403/404 RESOURCE_UNAVAILABLE | mã not-found sẵn có của repo |
| 409 VERSION_CONFLICT | `VERSION_CONFLICT` — **đã có** |
| Gửi trùng `requestId` | `IDEMPOTENCY_CONFLICT` — **đã có** |
| Chặn theo gói | `PLAN_UPGRADE_REQUIRED` — **đã có** |
| 422 RECIPE_NOT_ELIGIBLE | **thêm mới**, `HttpStatus.UNPROCESSABLE_CONTENT` |
| 200 NO_MATCH, reason SEARCH_LIMIT | **không phải mã lỗi** — là `reasonCodes` trong body, HTTP 200 |

---

## Nhắc lại hai ràng buộc không được quên

1. **Flyway đặt chỗ theo thứ tự nhánh**: V42 catalog · V43 hồ sơ · V44 recommendation ·
   V45 meal-plan · V46 shopping-list · V47 diary. Merge lệch thứ tự thì phải đổi tên file.
2. **`null` khác `0`** từ cột `amount_per_100g` cho tới thanh tiến trình trên UI. Thiếu dữ liệu
   thì tổng mang `PARTIAL`, không tô đầy, không thay bằng 0.
