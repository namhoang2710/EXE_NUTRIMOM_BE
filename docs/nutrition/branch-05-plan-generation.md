# Nhánh 5 — Lập thực đơn một ngày

> Đọc trước: [_CONTRACT.md](_CONTRACT.md). Liên quan: [DECISIONS.md](DECISIONS.md) QĐ-01, QĐ-02.

**Mục tiêu:** ghép món thành thực đơn một ngày, kiểm tra toàn kế hoạch, trả bản xem trước lưu được.
Nhánh nặng nhất, dễ trượt tiến độ nhất.

| | |
|---|---|
| Nhánh git | `feature/meal-plan-generation` |
| Cắt từ | nhánh 4 |
| Migration | lấy số lúc bắt đầu nhánh — xem `_CONTRACT.md` mục 2 |
| Demo được | Mẹ bầu bấm "Tạo thực đơn hôm nay", xem trước, chỉnh, lưu |

**Chỉ làm một ngày.** Ba và bảy ngày thuộc nhánh 9.

---

## 1. Bảng

| Bảng | Cột |
|---|---|
| `app.meal_plans` | `id`, `user_id`, `start_date`, `end_date`, `generation_status`, `lifecycle_state`, `plan_version`, `profile_version`, `rules_version` |
| `app.meal_slots` | `id`, `plan_id`, `local_date`, `meal_type`, **`sequence`**, `template_code` |
| `app.meal_plan_items` | `id`, `slot_id`, `recipe_id`, `recipe_version`, `servings`, `locked`, `nutrient_snapshot`, `cost_snapshot` |
| `app.meal_plan_previews` | `id`, `user_id`, `request_id`, `profile_version`, `rules_version`, `status`, `payload` JSONB, `created_at`, `expires_at` |

### `meal_slots.sequence` (QĐ-02)

`sequence SMALLINT NOT NULL DEFAULT 1`. Unique đổi thành **bộ bốn**:
`(plan_id, local_date, meal_type, sequence)`.

- `BREAKFAST` / `LUNCH` / `DINNER`: luôn `sequence = 1`. Tạo slot thứ hai cùng loại → `VALIDATION_ERROR`.
- `SNACK`: `sequence` 1–3.

### `meal_plan_previews` (QĐ-01)

TTL **30 phút**. Unique `(user_id, request_id)` — đây chính là cách xử lý "gửi hai lần vì mạng chậm":
lần thứ hai trả **đúng bản ghi cũ**, không sinh bản thứ hai. Dọn bản hết hạn bằng job định kỳ, hoặc
xóa lười lúc đọc.

### Ràng buộc

- Khóa ngoại của bản ghi cá nhân phải dẫn về **cùng `user_id`** qua kế hoạch hoặc hồ sơ.
- `servings > 0`. Ngân sách và thời gian nhập vào phải **> 0** — không cho đi qua phép chia với 0.
- `plan_version` tăng sau mỗi thao tác sửa kế hoạch.
- Index `meal_plans` theo `user_id` và ngày.

---

## 2. Tạo cấu trúc trước, chọn món sau

1. Tạo `meal_slots` cho ngày và các bữa được chọn, rồi chọn template.
2. Bữa nhiều món: tạo **vị trí theo vai trò**; mỗi vị trí có tập khẩu phần cho phép của món.
3. Đưa **món khóa** và **món đã ghi ăn** vào ngữ cảnh **trước**.
4. Bộ sinh **không** nhân khẩu phần liên tục và vô hạn để ép đủ vi chất.

Món đã ăn dùng để gợi ý **phần còn lại** của ngày, nhưng chỉ nhật ký mới đóng góp vào tổng "đã ghi".
Đa dạng cập nhật **sau từng lựa chọn**, không dùng một danh sách điểm cố định cho tất cả.

### Template bữa

| Loại | Cấu trúc | Cách dùng |
|---|---|---|
| Sáng | Một món hoàn chỉnh, hoặc món chính + phần ăn kèm | Ưu tiên ít bước; không bắt đổi món sáng mỗi ngày |
| Trưa và tối | Một món hoàn chỉnh, hoặc tinh bột + đạm + rau | Mỗi vai trò chọn từ nhóm tương thích |
| Phụ | Một hoặc hai món thuộc template bữa phụ | **Không** coi bữa phụ là thay thế bữa chính |

**BR06:** mỗi bữa có một món hoàn chỉnh **hoặc** một tập vai trò theo template — không trộn ngẫu nhiên.

Tỉ lệ phân bổ tham khảo: **25% sáng · 30% trưa · 30% tối · 15% bữa phụ tổng cộng**. Đây là giả định
kỹ thuật dùng để xếp hạng, **không phải quy tắc y khoa**. Bữa phụ chia đều cho `snack_count`.

**TC23:** chỉ lập trưa và tối thì **không chuẩn hóa hai bữa đó lên thành toàn bộ năng lượng ngày** —
người dùng vẫn ăn các bữa ngoài kế hoạch. Giao diện ghi rõ "đây là kế hoạch một phần ngày".

---

## 3. Beam search

Tham số khởi đầu, **chỉnh lại bằng bộ dữ liệu đồ án**:

| Tham số | Giá trị |
|---|---|
| Ứng viên mỗi vị trí sau lọc | tối đa **15** |
| Beam giữ sau mỗi bước | tối đa **20** |
| Mở rộng khi thiếu phương án | lên **30**, vẫn trong hạn thời gian |
| Hạn thời gian một ngày | **2 giây** |
| Hạn thời gian bảy ngày | **5 giây** (nhánh 9) |

### Tám bước

1. Đọc hồ sơ và **đóng băng `profile_version`** cho yêu cầu này.
2. Tạo vị trí ngày/bữa, đưa các mục được giữ vào kế hoạch.
3. Lọc, tính điểm, lấy ứng viên **đúng loại bữa và vai trò**.
4. Mở rộng các kế hoạch từng phần bằng khẩu phần hợp lệ của ứng viên.
5. Loại phương án vi phạm **điều kiện cứng**; chấm điểm toàn phương án.
6. Giữ beam tốt nhất, tiếp tục cho vị trí kế tiếp.
7. Kiểm tra lại toàn bộ kế hoạch; tính tổng, độ bao phủ dữ liệu và ghi chú.
8. Trả kết quả lưu được, **hoặc** trả lý do không có phương án.

**Điều kiện cứng:** thành phần dị ứng · giới hạn ăn bắt buộc · vai trò cần có trong template · khẩu
phần hợp lệ · mục đã khóa · giới hạn thời gian/ngân sách **nếu người dùng bật**.

---

## 4. Chấm điểm toàn kế hoạch

Đánh giá dinh dưỡng ở **cấp ngày**:

```
J = 0,40·Nutrition_penalty + 0,25·Preference_penalty + 0,15·Variety_penalty
  + 0,10·Cost_penalty + 0,10·Time_penalty
```

Chọn phương án **J nhỏ hơn**, sau khi đã lọc điều kiện cứng.

Mọi penalty chặn vào **0–1** trước khi cộng:

```
Preference_penalty = 1 − trung bình(P) / 100
Variety_penalty    = 1 − trung bình(V) / 100 trên kế hoạch
Cost_penalty       = clamp(tổng chi phí / ngân sách − 1, 0, 1)
Time_penalty       = trung bình clamp(thời gian toàn bữa / giới hạn − 1, 0, 1)
E_penalty          = clamp(abs(năng lượng ngày − EER) / EER, 0, 1)
Micro_penalty      = trung bình max(0, 1 − known_amount / target)   -- chỉ chất có dữ liệu đầy đủ
```

`Nutrition_penalty` = trung bình hai nhánh năng lượng và vi chất **đánh giá được**. Không có nhánh nào
→ **bỏ toàn thành phần** và ghi rõ giới hạn.

**Tiêu chí không có dữ liệu thì bỏ, trọng số còn lại chuẩn hóa.** Dữ liệu thiếu **không** được dùng để
xác nhận đạt mục tiêu.

**Không chọn món bổ sung hoặc tăng quá mức một thực phẩm để làm điểm vi chất đẹp.**

### Thời gian bữa nhiều món

MVP ước tính **bảo thủ**: thời gian bữa = **tổng** thời gian các món. Chưa giả định các bước nấu chạy
song song. Hiển thị ở mức món, nhưng `max_minutes_per_meal` kiểm ở mức **toàn bữa**.

Bật `strict_budget` mà thiếu giá, hoặc `strict_time` mà thiếu thời gian → yêu cầu bổ sung hoặc tắt
giới hạn, **để người dùng chọn**. Không coi dữ liệu thiếu là đã đạt giới hạn.

---

## 5. Trạng thái

### `generation_status` — kết quả sinh

| Giá trị | Nghĩa | Trình bày |
|---|---|---|
| `READY` | Hoàn thành vị trí và đạt tiêu chí sản phẩm | "Thực đơn đã sẵn sàng để bạn xem và lưu" |
| `READY_WITH_NOTES` | Đạt điều kiện cứng nhưng còn ưu tiên mềm hoặc thiếu dữ liệu | "Có vài điểm cần xem" — chỉ rõ chi phí / thời gian / dữ liệu |
| `NO_MATCH` | Không hoàn thành vị trí theo điều kiện cứng | Chỉ ra **bữa nào** và **bộ lọc nào** gây giới hạn |

### `lifecycle_state` — trạng thái lưu

`SAVED` | `NEEDS_UPDATE`. Bản xem trước **chưa phải** kế hoạch đã lưu.

**Hai nhóm trạng thái này không được dùng thay cho nhau.** `nutrition_coverage`
(`COMPLETE` | `PARTIAL`) là **thuộc tính riêng**, không đồng nghĩa với trạng thái kế hoạch.

`READY` **không phải chứng nhận y tế**.

### Hết thời gian tìm kiếm

Trả phương án hoàn chỉnh tốt nhất kèm ghi chú. Chưa có phương án hoàn chỉnh → `NO_MATCH` với
`reason = SEARCH_LIMIT`, giải thích **đã hết thời gian tìm**, **không khẳng định không tồn tại nghiệm**.

**BR14: không nới lỏng dị ứng khi thiếu lựa chọn.** Gợi ý người dùng tăng thời gian, đổi món không
thích, hoặc bỏ giới hạn ngân sách nếu họ đã bật giới hạn cứng.

---

## 6. Endpoint

```
POST /api/v1/meal-plans/preview      tạo bản xem trước có thời hạn
POST /api/v1/meal-plans              lưu bản xem trước; kiểm tra lại profile_version
GET  /api/v1/meal-plans/{id}         kế hoạch và tổng dự kiến
```

### Request tạo bản xem trước

Ví dụ JSON trong đặc tả là camelCase — **đổi sang snake_case** theo `_CONTRACT.md`:

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

`day_count` nhánh này **chỉ nhận 1**. `snack_count` 1–3, mặc định 1 (QĐ-02). `start_date` hiểu theo
múi giờ hồ sơ. `request_id` và `seed` phục vụ chống gửi trùng và tái lập kết quả thử nghiệm —
**không phải dữ liệu sức khỏe**.

### Response

Trả `preview_id`, `status`, `profile_version`, `rules_version`, `nutrition_coverage`, `notes`,
`days[].slots[].items[]` (kèm `reason_codes`), `days[].nutrition` (mỗi chất có
`known_value` / `unit` / `coverage`), `days[].estimated_cost_vnd`.

Khi lưu: kiểm `expires_at > now` **và** `profile_version` khớp hiện tại, nếu lệch thì **không lưu**.

**Nút tạo chuyển sang trạng thái xử lý và chặn nhấn trùng.** Lỗi mạng cho thử lại với **cùng
`request_id`**.

---

## Test bắt buộc

| TC | Dữ liệu | Kết quả mong đợi |
|---|---|---|
| TC18 | Không có ứng viên cho một vai trò | `NO_MATCH` **nêu rõ vai trò và bộ lọc** gây giới hạn |
| TC19 | Hết thời gian, chưa có kế hoạch hoàn chỉnh | `SEARCH_LIMIT`; không kết luận không tồn tại nghiệm |
| TC20 | Tài khoản khác truy cập `plan_id` | Không đọc, không sửa được |
| TC23 | Chỉ lập trưa và tối | Không coi hai bữa là toàn bộ lượng ăn ngày |
| TC24 | Số phần âm hoặc 0 | Báo lỗi **tại trường**, không lưu |
| — | Gửi hai lần cùng `request_id` | Trả cùng một bản xem trước, không sinh bản thứ hai |

Mục tiêu thời gian: **một ngày ≤ 2 giây** trên môi trường kiểm thử đã ghi cấu hình. Chưa đạt thì
chỉnh beam hoặc seed và **ghi kết quả thực tế**.

---

## KHÔNG làm trong nhánh này

Đổi món, khóa món, tạo lại, hoàn tác (nhánh 6) · ba và bảy ngày (nhánh 9) · mua sắm · nhật ký ·
tự tính tổng — **gọi NutritionCalculator của nhánh 2**.

---

## Xong là khi

- [ ] Migration chạy sạch; `meal_slots` có `sequence`, unique bộ bốn
- [ ] Sáu TC trên có test, xanh
- [ ] Ba `generation_status` đều tái hiện được bằng fixture
- [ ] Bản xem trước hết hạn sau 30 phút, lưu lúc đó bị từ chối
- [ ] Ghi lại thời gian sinh thực tế cho một ngày
- [ ] Cập nhật `docs/NUTRITION_API.md`
- [ ] Không tự commit, không tự push
