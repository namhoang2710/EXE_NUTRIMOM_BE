# Nhánh 9 — Thực đơn ba và bảy ngày

> Đọc trước: [_CONTRACT.md](_CONTRACT.md). Liên quan: [DECISIONS.md](DECISIONS.md) QĐ-04.

**Mục tiêu:** mở rộng `day_count` lên 3 và 7, thêm ràng buộc theo tuần và đa dạng liên ngày.

| | |
|---|---|
| Nhánh git | `feature/meal-plan-multiday` |
| Cắt từ | nhánh 6 |
| Migration | không có |
| Demo được | Lập thực đơn 7 ngày, món không lặp dồn, kiểm được ràng buộc cá theo tuần |

Chạy song song được với nhánh 10.

---

## 1. Phạm vi

`day_count` nhận **1, 3 hoặc 7** (nhánh 5 chỉ nhận 1). Không nhận giá trị khác trong MVP.

---

## 2. Ràng buộc theo tuần

Khi bật quy tắc có giới hạn thực phẩm theo tuần, điều kiện cứng phải kiểm **trên toàn kế hoạch**,
không chỉ từng ngày.

### Cá — ví dụ cụ thể

FDA/EPA phân loại lựa chọn và tần suất **theo tuần**. Khi bật quy tắc này trong bộ mẫu:

- Kiểm **tổng lượng và nhóm** của cả kế hoạch bảy ngày.
- **Không** chỉ kiểm tên món.
- **Không** coi mọi loại cá là giống nhau — giữ đúng loại và nhóm dữ liệu.

---

## 3. Đa dạng liên ngày

Công thức `V` ở nhánh 4 đã có sẵn ba thành phần liên ngày — nhánh này làm chúng **thật sự hoạt động**
trên nhiều ngày:

```
V = max(0, 100 − 40·I_recipe − 20·I_mainIngredient − 10·n_week)
```

- `I_recipe` = 1 nếu món xuất hiện trong **hai ngày gần đây**
- `I_mainIngredient` = 1 nếu nguyên liệu chính xuất hiện **ngày trước**
- `n_week` = số lần món đã có trong **tuần đang tạo**, không tính lần đang xét

Đa dạng cập nhật **sau từng lựa chọn**, không dùng một danh sách điểm cố định cho tất cả các ngày.

Món người dùng **chủ động lập lịch lặp** vẫn được giữ; chỉ phần kế hoạch còn lại tăng đa dạng.

Ghi chú lặp món bật khi **cùng món xuất hiện trong hai ngày gần đây**, ngoài các mục lặp người dùng
chủ động giữ.

---

## 4. Hiệu năng

| Mục tiêu | Giá trị |
|---|---|
| Hạn thời gian bảy ngày | **5 giây** trên môi trường kiểm thử đã ghi cấu hình |
| Ứng viên mỗi vị trí | 15, mở lên 30 khi thiếu phương án |
| Beam | 20 |

Chưa đạt thì **chỉnh beam hoặc seed và ghi kết quả thực tế** — không im lặng bỏ qua.

Hết thời gian → trả phương án hoàn chỉnh tốt nhất kèm ghi chú; chưa có phương án hoàn chỉnh →
`NO_MATCH` với `reason = SEARCH_LIMIT`.

---

## 5. Chặn theo gói — chỗ duy nhất của cả module (QĐ-04)

MVP **không gate gì**. Khi quyết định bán, đây là chỗ cắm, **không phải nhánh nào khác**:

```java
entitlements.requireFeature(userId, PlanFeature.PERSONALISED_GUIDANCE);
```

Theo đúng mẫu `CalendarReminderService` đang dùng. Lỗi trả `PLAN_UPGRADE_REQUIRED` (403), đã có sẵn.

**Không thêm giá trị mới vào `PlanFeature`** — enum đó khớp cứng thứ tự với `pricing-catalog.ts` bên
FE, thêm một dòng là dán sai nhãn cả bảng giá.

Chỉ gate `day_count != 1`. **Không bao giờ** gate lọc dị ứng, cảnh báo thành phần, điều kiện chế biến.

---

## 6. Hiển thị (phối hợp với FE)

- Điện thoại: **thanh chọn ngày theo chiều ngang**.
- Máy tính: **bảng tuần**.
- **Không ép một bảng bảy cột vào màn hình nhỏ.**
- Ngày có ghi chú hoặc dữ liệu thiếu: đánh dấu bằng **văn bản**, không chỉ khác màu.

---

## Test bắt buộc

| TC | Dữ liệu | Kết quả mong đợi |
|---|---|---|
| — | `day_count = 7` | Sinh xong trong 5 giây; ghi lại thời gian thực tế |
| — | Quy tắc cá theo tuần bật | Kiểm tổng lượng và nhóm **toàn kế hoạch**, không theo tên món |
| — | Cùng món hai ngày liên tiếp | `V` giảm đúng 40 điểm; có ghi chú lặp món |
| — | `day_count = 5` | Từ chối, `VALIDATION_ERROR` |
| TC23 | Chỉ lập trưa và tối, 7 ngày | Vẫn ghi "kế hoạch một phần ngày" cho từng ngày |

---

## KHÔNG làm trong nhánh này

Thêm `PlanFeature` mới · gate bất kỳ tính năng an toàn nào · đổi hợp đồng API của nhánh 5 ngoài
`day_count`.

---

## Xong là khi

- [ ] `day_count` 1 / 3 / 7 chạy; giá trị khác bị từ chối
- [ ] Năm TC trên có test, xanh
- [ ] Ghi lại số đo thời gian thực tế cho 1 ngày và 7 ngày vào `docs/NUTRITION_API.md`
- [ ] Không tự commit, không tự push
