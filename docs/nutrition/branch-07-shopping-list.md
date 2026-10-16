# Nhánh 7 — Danh sách mua sắm

> Đọc trước: [_CONTRACT.md](_CONTRACT.md).

**Mục tiêu:** biến thực đơn thành danh sách nguyên liệu đi chợ được.

| | |
|---|---|
| Nhánh git | `feature/nutrition-shopping-list` |
| Cắt từ | nhánh 6 |
| Migration | **V46** |
| Demo được | Tạo thực đơn → mở mua sắm → tick "đã có" → đổi món → danh sách cập nhật |

Chạy song song được với nhánh 8. **Không mở cùng lúc với nhánh 6.**

---

## 1. Bảng (V46)

| Bảng | Cột |
|---|---|
| `app.shopping_lists` | `id`, `user_id`, `plan_id`, `plan_version`, `date_range` |
| `app.shopping_list_items` | `id`, `list_id`, `ingredient_id` **nullable**, `state`, `quantity`, `unit`, `have_quantity`, `purchased_quantity`, `custom_text` |

`ingredient_id` nullable vì người dùng được **thêm dòng tự nhập** cho nhu cầu khác — dòng đó
**không ảnh hưởng dinh dưỡng**.

---

## 2. Quy tắc gộp — phần dễ sai nhất của nhánh này

Nguyên liệu gộp theo **danh tính VÀ trạng thái tương thích**, chia thành nhóm: rau quả · thịt cá
trứng · ngũ cốc · các nhóm phù hợp khác.

### Ba điều cấm

1. **Không cộng 100 g gạo sống với 100 g cơm chín.** Khác `food_state` thì khác dòng.
2. **Không tự đổi thìa sang gram** nếu thiếu quy đổi. Chỉ hỗ trợ đơn vị **đã quy đổi được**.
3. **Không coi mọi loại cá là giống nhau** — giữ đúng loại và nhóm dữ liệu đang áp dụng.

Mỗi dòng hiển thị: lượng cần · **món sử dụng** · trạng thái đã có / đã mua.

### Sau khi đổi món

Các dòng **giữ trạng thái "đã mua"** nếu nguyên liệu vẫn tồn tại, nhưng dòng tăng lượng hiện ghi chú
**"Cần thêm"** (TC22). Không reset cả danh sách.

### Giá

Giá tổng là **ước tính từ dữ liệu mẫu**, không phải báo giá cửa hàng. Thiếu giá → hiển thị
"Chưa có giá", tổng chi phí kèm nhãn **chưa đầy đủ**. Không kết nối cửa hàng trong MVP.

### Mua theo gói

Nhập số lượng đã có → xem lượng còn cần. **Chỉ hỗ trợ đơn vị đã quy đổi được.**

---

## 3. Nhắc dị ứng ở mức nguyên liệu

Bài học từ Mealime: **lọc công thức không thay thế việc đọc nhãn sản phẩm** khi mua gia vị hoặc thực
phẩm đóng gói. Giữ **một nhắc nhở ngắn** trong chi tiết nguyên liệu có liên quan — ví dụ gia vị có
khả năng chứa tôm vẫn được nhắc đọc thành phần.

---

## 4. Endpoint

```
GET   /api/v1/meal-plans/{id}/shopping-list   tổng hợp nguyên liệu theo phạm vi ngày
PATCH /api/v1/shopping-list-items/{id}        đã có / đã mua / dòng tự nhập
```

Chọn phạm vi ngày. Bỏ chọn món không cần mua. Thêm dòng tự nhập.

---

## Test bắt buộc

| TC | Dữ liệu | Kết quả mong đợi |
|---|---|---|
| TC10 | Gạo sống và cơm chín cùng danh sách | **Không gộp** thành một lượng gram sai trạng thái |
| TC22 | Đổi món làm tăng lượng đã mua | Dòng nguyên liệu thể hiện **lượng cần thêm**, giữ trạng thái đã mua |
| — | Nguyên liệu dùng thìa, chưa có quy đổi | Không tự đổi sang gram |
| — | Nguyên liệu chưa có giá | Hiển thị "Chưa có giá"; tổng kèm nhãn chưa đầy đủ |
| — | Dòng tự nhập | Không ảnh hưởng tổng dinh dưỡng |

---

## KHÔNG làm trong nhánh này

Kết nối cửa hàng · thanh toán · giao hàng · quét mã vạch · nhật ký (nhánh 8).

---

## Xong là khi

- [ ] V46 chạy sạch
- [ ] Năm TC trên có test, xanh
- [ ] Đổi món ở nhánh 6 làm danh sách cập nhật theo `plan_version` mới
- [ ] Cập nhật `docs/NUTRITION_API.md`
- [ ] Không tự commit, không tự push
