# Nhánh 3 — Hồ sơ ăn uống

> Đọc trước: [_CONTRACT.md](_CONTRACT.md). Liên quan: [DECISIONS.md](DECISIONS.md) QĐ-03.

**Mục tiêu:** mẹ bầu khai dị ứng, khẩu vị và sinh hoạt trong 5 bước ngắn, bỏ qua được bước tùy chọn
mà vẫn dùng được tính năng.

| | |
|---|---|
| Nhánh git | `feature/nutrition-profile` |
| Cắt từ | nhánh 1 |
| Migration | lấy số lúc bắt đầu nhánh — xem `_CONTRACT.md` mục 2 |
| Demo được | Onboarding 5 bước, lưu xong hiện tuần thai đúng |

Chạy song song được với nhánh 2.

---

## 1. Tái sử dụng, không tạo mới

`PregnancyProfile` **đã có** trong repo (V4). Nhánh này **không** tạo lại hồ sơ thai kỳ — chỉ đọc
`anchor_date`, `gestational_age_days_at_anchor`, `due_date` và thêm `profile_version` nếu chưa có.

Đăng nhập và thông tin thai kỳ sẵn có đều tái sử dụng.

---

## 2. Bảng mới

| Bảng | Cột |
|---|---|
| `app.eating_preferences` | `profile_id` FK, `diet_pattern`, `hard_exclusions`, `tolerance_tags`, `disliked_ingredient_ids`, `daily_budget_vnd`, `max_minutes_per_meal`, `energy_inputs` |
| `app.user_allergens` | `profile_id` FK, `allergen_id` FK — **unique hai mã** |
| `app.user_recipe_preferences` | `user_id` FK, `recipe_id` FK, `preference_type` — **unique `(user_id, recipe_id)`** |

### `user_recipe_preferences` — KHÔNG có cột `expires_at` (QĐ-03)

Đặc tả gốc cho bảng này một cột `expires_at`, trùng vai trò với `meal_feedbacks`. Đã chốt tách:

- **Bảng này** = lựa chọn chủ động, vĩnh viễn: `preference_type` là `FAVORITE` | `BLOCKED`.
  `BLOCKED` là nút "Không muốn gợi ý món này". Sống đến khi người dùng tự đổi.
- Phản hồi sau bữa ăn **có hạn** thuộc `app.meal_feedbacks` ở nhánh 8.

`energy_inputs` gom: `age_years`, `height_cm`, `pre_pregnancy_weight_kg`, `current_weight_kg`,
`activity_category`. Toàn bộ tùy chọn.

---

## 3. Năm bước onboarding

| Bước | Nội dung | Cách giảm thao tác |
|---|---|---|
| 1 Thai kỳ | Ngày dự sinh **hoặc** tuần + ngày thai tại ngày ghi nhận | Lấy từ hồ sơ có sẵn; **không bắt nhập hai cách cùng lúc** |
| 2 Thành phần cần tránh | Có dị ứng đã biết / không có / chưa rõ | Chọn chip và tìm nguyên liệu |
| 3 Khẩu vị | Chế độ ăn, nguyên liệu không thích, món quen, dung nạp mùi/kết cấu | Bỏ qua được |
| 4 Sinh hoạt | Số bữa cần lập, thời gian nấu, ngân sách dự kiến | Lựa chọn nhanh, chỉnh lại được lúc tạo kế hoạch |
| 5 Tùy chọn | Thông tin ước tính năng lượng | Nút "Để sau", không chặn chức năng cơ bản |

### Dị ứng — ba trạng thái, không phải boolean

`allergy_status`: `KNOWN_NONE` | `KNOWN_LIST` | `UNKNOWN`

**BR01: không tự xác nhận người dùng không có dị ứng.** `UNKNOWN` vẫn xem được nội dung và công thức,
nhưng danh sách gợi ý **không được gắn nhãn** "đã kiểm tra theo dị ứng của bạn". Trước khi lưu thực
đơn cá nhân hóa, giao diện nhắc xác nhận trạng thái. Đây là kiểm tra dữ liệu đầu vào, **không có
actor duyệt hồ sơ**.

---

## 4. Tính tuổi thai

```
tuổi thai hôm nay = gestational_age_days_at_anchor + số ngày lịch đã trôi qua
                    (trong múi giờ của người dùng)

hiển thị = floor(days / 7) tuần  và  days mod 7 ngày
```

- Dùng ngày dự sinh thì quy đổi theo mốc **280 ngày**.
- **Không tự thay ngày dự sinh người dùng đã xác nhận** bằng giá trị khác.
- Ngày dự sinh qua đi **không** đồng nghĩa đã sinh → hỏi cập nhật trạng thái và **tạm dừng tự tạo kế
  hoạch mới** khi thông tin không còn phù hợp.
- Múi giờ: dùng lại quy ước `local_date` của module calendar, không tự chế.

---

## 5. Endpoint

```
GET /api/v1/me/nutrition-profile    hồ sơ ăn uống + mốc thai kỳ hiện tại
PUT /api/v1/me/nutrition-profile    cập nhật; trả profile_version mới
```

`PUT` trả về `profile_version` mới **và** danh sách mục tương lai cần kiểm tra lại. Nhánh này chỉ cần
trả danh sách rỗng — việc đánh dấu `NEEDS_UPDATE` thuộc nhánh 10.

`profile_version` **tăng khi lưu** bất kỳ trường nào ảnh hưởng gợi ý.

---

## Test bắt buộc

| TC | Dữ liệu | Kết quả mong đợi |
|---|---|---|
| TC04 | 13 tuần 6 ngày → 14 tuần 0 ngày | Giai đoạn đổi **đúng ranh giới**, không lệch một ngày |
| TC05 | Bỏ qua chiều cao và cân nặng | Dùng được chế độ nhanh; không có phần trăm năng lượng |
| — | `allergy_status = UNKNOWN` | Vẫn xem được nội dung; không gắn nhãn đã kiểm tra dị ứng |
| — | Lưu hồ sơ | `profile_version` tăng đúng một |

---

## KHÔNG làm trong nhánh này

Lọc/xếp hạng · lập kế hoạch · đánh dấu `NEEDS_UPDATE` cho mục tương lai (nhánh 10) ·
tạo lại `PregnancyProfile`.

---

## Xong là khi

- [ ] Migration chạy sạch; `user_recipe_preferences` **không có** cột `expires_at`
- [ ] Hai endpoint chạy, chặn được tài khoản khác (BR19)
- [ ] Bỏ qua bước 3 và 5 vẫn lưu được hồ sơ và vẫn dùng được danh mục
- [ ] Bốn TC trên có test, xanh
- [ ] Cập nhật `docs/NUTRITION_API.md`
- [ ] Không tự commit, không tự push
