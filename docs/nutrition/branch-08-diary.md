# Nhánh 8 — Nhật ký và phản hồi

> Đọc trước: [_CONTRACT.md](_CONTRACT.md). Liên quan: [DECISIONS.md](DECISIONS.md) QĐ-03.

**Mục tiêu:** ghi món **thực tế đã ăn**, tách hẳn khỏi kế hoạch, và khép vòng lặp phản hồi về Ranker.

| | |
|---|---|
| Nhánh git | `feature/nutrition-diary` |
| Cắt từ | nhánh 5 |
| Migration | **V47** |
| Demo được | Ăn nửa phần → nhật ký tính một nửa, kế hoạch vẫn một phần |

Chạy song song được với nhánh 7.

---

## 1. Bất biến 3 — hai tập dữ liệu độc lập

```
meal_plan_items              diary_entries
Khẩu phần dự kiến    ──ghi──▶  Khẩu phần thực tế đã xác nhận
Không tự thành lượng đã ăn     Có thể khác kế hoạch
```

**Sửa kế hoạch không viết lại nhật ký.** Tổng "đã ghi" **chỉ** lấy từ nhật ký. Tổng "dự kiến" vẫn
tổng hợp riêng. Hai chỉ số hiển thị tách bạch, **không cộng hai tập vào nhau**.

Kế hoạch hết ngày vẫn xem được trong lịch sử, **không tự tạo nhật ký**. Các bữa chưa ghi
**không tự thành đã ăn**.

---

## 2. Bảng (V47)

| Bảng | Cột |
|---|---|
| `app.diary_entries` | `id`, `user_id`, `local_date`, `meal_type`, `recipe_id` **nullable**, `recipe_snapshot`, `actual_servings`, `source_plan_item_id` **nullable**, `request_id` |
| `app.meal_feedbacks` | `id`, `user_id`, `recipe_id`, `type`, `created_at`, `expires_at` **nullable** |

`source_plan_item_id` là **nguồn tùy chọn** — nhật ký ghi được món **ngoài thực đơn**.

`recipe_snapshot` lưu tên, đơn vị, khẩu phần và giá trị **đã dùng tại thời điểm ghi**. Seed đổi phiên
bản thì lịch sử giữ snapshot cũ (TC21).

Index: `diary_entries` theo `user_id` và `local_date`.

### `meal_feedbacks` — hạn theo loại (QĐ-03)

| `type` | `expires_at` | Tác động lên P (nhánh 4) |
|---|---|---|
| `TASTY` | `null` | +10 |
| `DISLIKE` | `null` | −25 |
| `HARD_NOW` | `now + 7 ngày` | −20 |

Chỉ cái **tạm thời** mới hết hạn. Lựa chọn chủ động vĩnh viễn (`FAVORITE`, `BLOCKED`) nằm ở
`app.user_recipe_preferences` của nhánh 3, **không có `expires_at`**.

**Lựa chọn tạm thời không được tự biến thành dị ứng.**

---

## 3. Ghi nhật ký (UC05)

**BR16: nhật ký được tạo sau xác nhận ngày, bữa và số phần.**

- Nhấn "Đã ăn" → mở **xác nhận ngắn**: món, ngày, bữa, số phần.
- Mặc định lấy phần từ kế hoạch, **nhưng phải có thao tác xác nhận**.
- Thao tác "ăn một nửa" = `0,5 × phần kế hoạch`.
- **Không tự ghi tất cả các bữa cuối ngày.**

### Nguyên liệu đổi đáng kể

Hỏi chọn **biến thể có sẵn**, hoặc **đánh dấu ước tính chưa đầy đủ**. Không tự cho số liệu chuẩn của
công thức gốc là chính xác cho món khác.

### Món ngoài bộ dữ liệu

Lưu ghi chú và lượng đã ăn nếu cần, nhưng **không tự sinh vi chất từ tên món**.

### Sửa và xóa

Ghi sai chỉnh hoặc xóa bằng thao tác rõ ràng. Xóa → tổng đã ghi đổi, **tổng dự kiến không đổi** (TC17).

---

## 4. Phản hồi sau bữa

Một lần chạm: **Hợp khẩu vị** | **Không thích** | **Khó ăn lúc này**.

---

## 5. Gợi ý sau khi đã ăn (UC08)

Đã có nhật ký → tính **phần còn lại** của năng lượng hoặc các mục tiêu **có dữ liệu**; gợi ý một bữa
phụ hay món bổ sung.

**Cấm:**
- Không ép bù toàn bộ một vi chất bằng một món.
- Không đề xuất tăng liều bổ sung.
- Nhật ký quá thiếu dữ liệu → hiển thị **gợi ý đa dạng thông thường**, không khẳng định đã đánh giá
  khẩu phần cả ngày.

Nếu đã ghi một phần trong ngày, khu vực gợi ý ưu tiên **phần còn lại** theo dữ liệu hiện có;
**không sửa món đã ăn**.

---

## 6. Endpoint

```
GET    /api/v1/diary?date=YYYY-MM-DD   món thực tế đã ghi và tổng theo ngày
POST   /api/v1/diary-entries           tạo nhật ký bằng request_id để chống ghi trùng
PATCH  /api/v1/diary-entries/{id}      sửa món hoặc phần thực tế; trả tổng mới
DELETE /api/v1/diary-entries/{id}      xóa bản ghi; tính lại tổng
POST   /api/v1/meal-feedback           yêu thích, không thích, hoặc khó ăn tạm thời
```

`request_id` nằm **trong body**, unique cùng `user_id`. Gửi lại cùng `request_id` → trả **đúng bản ghi
cũ**, không tạo bản thứ hai.

`GET /diary` trả **hai tổng tách biệt**: dự kiến và đã ghi, mỗi chất kèm `coverage`.
Nhật ký thiếu bữa → ghi **"Tổng của các món đã ghi"**, không ghi "Tổng lượng ăn cả ngày".

---

## Test bắt buộc

| TC | Dữ liệu | Kết quả mong đợi |
|---|---|---|
| TC15 | Ghi 0,5 phần từ kế hoạch 1 phần | Nhật ký tính **một nửa**; kế hoạch vẫn **1 phần** |
| TC16 | Gửi cùng `request_id` hai lần | **Chỉ một** `diary_entry` |
| TC17 | Xóa nhật ký | Tổng **đã ghi** đổi; tổng **dự kiến** không đổi |
| TC21 | Recipe seed thay phiên bản | Lịch sử nhật ký giữ **snapshot cũ** |
| — | Ghi món ngoài bộ dữ liệu | Không tự sinh vi chất từ tên món |
| — | Phản hồi `HARD_NOW` | Giảm ưu tiên 7 ngày; **không** thành dị ứng |

---

## KHÔNG làm trong nhánh này

Tự ghi nhật ký thay người dùng · đánh dấu lại kế hoạch khi đổi hồ sơ (nhánh 10) · tự tính tổng —
**gọi NutritionCalculator của nhánh 2**.

---

## Xong là khi

- [ ] V47 chạy sạch
- [ ] Sáu TC trên có test, xanh
- [ ] `GET /diary` trả hai tổng tách biệt, không cộng lẫn
- [ ] Phản hồi gần nhất ảnh hưởng đúng điểm P ở nhánh 4
- [ ] Cập nhật `docs/NUTRITION_API.md`
- [ ] Không tự commit, không tự push
