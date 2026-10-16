# Nhánh 6 — Đổi món, khóa món, tạo lại, hoàn tác

> Đọc trước: [_CONTRACT.md](_CONTRACT.md).

**Mục tiêu:** mẹ bầu sửa được một lựa chọn mà **không phải tạo lại toàn bộ kế hoạch**.

| | |
|---|---|
| Nhánh git | `feature/meal-plan-edit` |
| Cắt từ | nhánh 5 |
| Migration | không có |
| Demo được | Giữ bữa sáng, đổi món mặn bữa trưa, tổng ngày cập nhật ngay |

**Không mở cùng lúc với nhánh 7** — cả hai cùng ghi vào plan service và shopping list version.

---

## 1. Trình tự đổi món

```
Mẹ bầu  →  Frontend         →  Backend              →  Database
  Chọn đổi món
            Xin lựa chọn cùng vai trò
                               Đọc hồ sơ và kế hoạch
            Trả lựa chọn và chênh lệch
  Chọn món thay thế
            Gửi món và expected_version
                               Kiểm tra và lưu transaction
            Trả tổng cùng phiên bản mới
  Cập nhật màn hình và mua sắm
```

### UC02 chi tiết

1. Lấy các lựa chọn **cùng bữa và cùng vai trò**.
2. **Kiểm tra lại hồ sơ** (không tin kết quả lọc cũ).
3. **Giữ nguyên những mục khác.**
4. Tính **tác động từng lựa chọn**: chênh lệch chi phí và thời gian so với món cũ.
5. Người dùng chọn → backend kiểm `plan_version` **và** `profile_version` → lưu trong **một
   transaction**.
6. Trả tổng dinh dưỡng, chi phí, `plan_version` mới, ghi chú và `shopping_list_version`.

**BR12:** đổi món **tính lại tổng ngày và các điều kiện liên quan của tuần**.

Không lưu món rồi mới tính tổng ở một yêu cầu rời — dữ liệu sẽ lệch nhau.

### Xung đột phiên bản

Phiên bản đã đổi ở thiết bị khác → **tải lại kế hoạch và yêu cầu chọn lại**. Không thay trên bản cũ,
không ghi đè âm thầm. Trả `VERSION_CONFLICT` (409).

Đổi món **đã ghi nhật ký** → chỉ đổi **kế hoạch**; nhật ký vẫn giữ món thực tế đã ghi (bất biến 3).

### Hoàn tác

`POST .../undo` dùng **phiên bản kế hoạch**, không ghi lại nhật ký. Hoàn tác phải **kiểm tra lại điều
kiện hiện tại** trước khi khôi phục lựa chọn cũ — hồ sơ có thể đã đổi trong lúc đó.

---

## 2. Giữ món và tạo lại (UC03)

- Khóa món bằng biểu tượng kèm nhãn "Giữ món này" → `meal_plan_items.locked = true`.
- **Tạo lại giữ nguyên món và khẩu phần đã khóa**, chỉ tìm phương án cho phần còn lại.
- **BR11: tạo lại không sửa mục đã ăn và không tự ghi nhật ký.**
- Mục khóa **không còn hợp lệ** sau thay đổi dị ứng → **báo mục đó cần đổi**, **không âm thầm mở khóa**.
- Không có cách hoàn thành quanh các mục khóa → cho người dùng **chọn mục muốn bỏ giữ**.

---

## 3. Chỉnh số phần

Cho chọn **0,5 · 1 · 1,5 · 2** phần, hoặc nhập lượng hợp lệ. Mỗi công thức cho biết một phần tương
đương bao nhiêu gram **nếu có dữ liệu**.

**BR07: khẩu phần phải > 0 và nằm trong miền số phần hỗ trợ của món.**

Đổi khẩu phần → **tổng ngày cập nhật ngay**.

---

## 4. Endpoint

```
GET   /api/v1/meal-plans/{id}/items/{itemId}/alternatives   lựa chọn cùng vai trò
PATCH /api/v1/meal-plans/{id}/items/{itemId}                đổi món / số phần / khóa
POST  /api/v1/meal-plans/{id}/regenerate                    tạo lại mục chưa khóa, chưa ăn
POST  /api/v1/meal-plans/{id}/undo                          khôi phục phiên bản trước
```

### Body PATCH (snake_case)

```json
{
  "expected_plan_version": 4,
  "expected_profile_version": 3,
  "recipe_id": 205,
  "recipe_version": 1,
  "servings": 1,
  "locked": false
}
```

Trình tự backend: kiểm chủ sở hữu và phiên bản → lọc món mới → áp thay đổi lên bản tạm → tính và
**kiểm tra toàn kế hoạch** → cập nhật kế hoạch cùng tổng trong **một transaction**.

### Mã lỗi

| Tình huống | Mã |
|---|---|
| Món vi phạm thành phần hoặc giới hạn bắt buộc | `RECIPE_NOT_ELIGIBLE` (422) — mã thêm ở nhánh 1 |
| `expected_plan_version` cũ | `VERSION_CONFLICT` (409) |
| Số phần âm, 0, hoặc ngoài miền | `VALIDATION_ERROR` (422), chỉ rõ trường |
| Không thuộc người dùng hoặc không tồn tại | 403 / 404 |

Món không phù hợp hồ sơ: vẫn **xem được thông tin**, nhưng nút thêm phải **giải thích thành phần
đang xung đột**.

---

## Test bắt buộc

| TC | Dữ liệu | Kết quả mong đợi |
|---|---|---|
| TC11 | Giữ món rồi tạo lại | Giữ đúng `recipe_version` và số phần của món hợp lệ |
| TC13 | Đổi món thành công | Tổng, nguyên liệu và `plan_version` **nhất quán** |
| TC14 | `expected_version` cũ | Trả **409**; **không thay dữ liệu** |
| TC24 | Số phần âm hoặc 0 | Báo lỗi tại trường, không lưu |
| — | Đổi món đã ghi nhật ký | Chỉ kế hoạch đổi; nhật ký giữ nguyên món đã ghi |
| — | Hoàn tác sau khi hồ sơ đổi | Kiểm tra lại điều kiện trước khi khôi phục |

---

## KHÔNG làm trong nhánh này

Mua sắm (nhánh 7) · nhật ký (nhánh 8) · nhiều ngày (nhánh 9) · đánh dấu `NEEDS_UPDATE` hàng loạt khi
đổi hồ sơ (nhánh 10).

---

## Xong là khi

- [ ] Bốn endpoint chạy, mọi thao tác sửa nằm trong transaction
- [ ] Sáu TC trên có test, xanh
- [ ] Không có đường nào ghi đè kế hoạch mà bỏ qua kiểm tra phiên bản
- [ ] Cập nhật `docs/NUTRITION_API.md`
- [ ] Không tự commit, không tự push
