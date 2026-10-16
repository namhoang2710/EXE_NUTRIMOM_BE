# Nhánh 2 — NutritionCalculator

> Đọc trước: [_CONTRACT.md](_CONTRACT.md).

**Mục tiêu:** một bộ tính dinh dưỡng duy nhất, thuần logic, test bằng fixture. Nhánh 5 (lập kế
hoạch), nhánh 6 (đổi món) và nhánh 8 (nhật ký) đều gọi lại bộ này — **không nơi nào tự tính tổng**
(bất biến 4).

| | |
|---|---|
| Nhánh git | `feature/nutrition-calculator` |
| Cắt từ | nhánh 1 |
| Migration | không có |
| Demo được | Test fixture: đổi số phần, thiếu dữ liệu vi chất, ước tính năng lượng |

Chạy song song được với nhánh 3.

---

## 1. Công thức tính dinh dưỡng

Chuẩn hóa về **gram phần ăn được**.

Ký hiệu: `q_i` = gram nguyên liệu i **đúng trạng thái** của bản ghi dinh dưỡng · `a_i,k` = lượng
chất k trên 100 g · `r_i,k` = hệ số giữ lại khi dùng mô hình từ nguyên liệu sống.

```
lượng chất k của công thức = Σ ( q_i / 100 × a_i,k × r_i,k )

lượng chất k một phần    = lượng của công thức / servings_yield
lượng chất k của mục     = lượng một phần × servings_selected
```

### Quy tắc trạng thái thực phẩm

- Bản ghi dinh dưỡng đã mô tả **thực phẩm chín** tương ứng → dùng `r = 1`, **không áp hao hụt lần nữa**.
- Chưa có hệ số giữ lại phù hợp → `r = 1` chỉ là **giả định ước tính**, phải ghi trong phương pháp của
  món. Không coi đó là số đo sau nấu.
- Chọn một phần theo gram từ **món chín** → dùng khối lượng chín tổng để xác định tỉ lệ. **Không** lấy
  gram nguyên liệu sống làm gram món chín.
- **Không quy đổi ml sang gram bằng một mật độ chung** cho mọi nguyên liệu.

---

## 2. Trạng thái dữ liệu — phần dễ làm sai nhất

Dữ liệu dinh dưỡng thiếu **không có nghĩa bằng 0**. Mỗi tổng trả về ba thứ:

```
{ known_value, missing_ingredient_ids, coverage_status }
```

- Một nguyên liệu thiếu giá trị chất k → tổng chất k **chỉ là phần đã biết**,
  `coverage_status = PARTIAL`.
- Đủ dữ liệu → `COMPLETE`.
- UI ghi "Chưa đủ dữ liệu cho chất này", **không tô thanh tiến trình như thể tổng hoàn chỉnh**.
- `q_k` thiếu vẫn lưu `null` (BR08, BR09).

---

## 3. Bảng vi chất tham chiếu

Tham chiếu NIH cho nhóm tuổi 19–50. Choline là **AI**, còn lại là **RDA**. Lưu trong
`nutritionRules.json` kèm mã phiên bản để tái lập.

| Chất | Mức mỗi ngày | Đơn vị lưu |
|---|---|---|
| Folate | 600 µg DFE | `mcg_dfe` |
| Sắt | 27 mg | `mg` |
| Canxi | 1.000 mg | `mg` |
| Choline | 450 mg | `mg` |
| Iốt | 220 µg | `mcg` |
| Vitamin D | 15 µg | `mcg` |

Không coi các mức này là phân phối cho một bữa, cũng không phải liều cần bổ sung. **DFE khác với
lượng axit folic ghi trên nhãn.** Không áp một ngưỡng tối đa chung cho mọi dữ liệu món ăn. MVP chỉ
ghi lượng **từ thực phẩm**, chưa nhập thuốc và thực phẩm bổ sung → không kết luận tổng nạp vào.

---

## 4. Ước tính năng lượng EER — tùy chọn

Chỉ bật khi người dùng nhập đủ: tuổi, chiều cao, cân nặng hiện tại, tuổi thai, nhóm vận động.
Áp cho nhóm tuổi 19–50 trong phạm vi dữ liệu đồ án.

Ký hiệu: `A` tuổi (năm) · `H` chiều cao (cm) · `W` cân nặng hiện tại (kg) · `G` tuổi thai (tuần) =
số ngày thai / 7. Kết quả kcal/ngày.

### Tam cá nguyệt 1

| Vận động | Công thức |
|---|---|
| Inactive | `584,90 − 7,01A + 5,72H + 11,71W` |
| Low active | `575,77 − 7,01A + 6,60H + 12,14W` |
| Active | `710,25 − 7,01A + 6,54H + 12,34W` |
| Very active | `511,83 − 7,01A + 9,07H + 12,56W` |

### Tam cá nguyệt 2 và 3

| Vận động | Công thức |
|---|---|
| Inactive | `1.131,20 − 2,04A + 0,34H + 12,15W + 9,16G + D` |
| Low active | `693,35 − 2,04A + 5,73H + 10,20W + 9,16G + D` |
| Active | `−223,84 − 2,04A + 13,23H + 8,15W + 9,16G + D` |
| Very active | `−779,72 − 2,04A + 18,45H + 8,73W + 9,16G + D` |

`D` chọn theo **BMI trước thai kỳ**: dưới 18,5 → `+300` · 18,5 đến dưới 25 → `+200` ·
25 đến dưới 30 → `+150` · từ 30 → `−50` kcal/ngày.

`D` là thành phần toán học của mô hình, **không phải thiết lập giảm cân**.

### Ràng buộc

- Công thức **đã gồm yếu tố thai kỳ** → **không cộng thêm 340 hoặc 450 kcal**.
- Không tự suy nhóm vận động từ số bước chân.
- Thiếu dữ liệu, giá trị không hợp lệ, hoặc cân nặng không rõ đơn vị → **chưa tính EER**, không tự
  điền giá trị giả.
- EER là ước tính có sai số, không phải chỉ định khẩu phần.
- Không có mục tiêu năng lượng thì **không hiển thị phần trăm hoàn thành**, không mặc định 2.000 kcal
  cho mọi người (BR20).

---

## Test bắt buộc

| TC | Dữ liệu | Kết quả mong đợi |
|---|---|---|
| TC08 | Tăng phần từ 1 lên 1,5 | Dinh dưỡng và nguyên liệu tăng đúng 1,5 lần **trước làm tròn** |
| TC09 | Một nguyên liệu thiếu folate | Folate là `PARTIAL`, phần thiếu **không** coi là 0 |
| TC05 | Bỏ qua chiều cao và cân nặng | Không tính EER, không có phần trăm năng lượng |
| — | Bản ghi món chín | `r = 1`, không áp hao hụt lần hai |

Làm tròn **chỉ ở lớp hiển thị**. Phép tính khớp fixture trước làm tròn.

---

## KHÔNG làm trong nhánh này

Endpoint (nhánh này thuần logic) · lọc/xếp hạng · lập kế hoạch · bất kỳ nơi thứ hai nào tính tổng.

---

## Xong là khi

- [ ] Bốn TC trên có test tự động, xanh
- [ ] Bảng vi chất và hệ số EER nạp từ `nutritionRules.json`, có mã phiên bản
- [ ] API nội bộ của calculator nhận snapshot nguyên liệu, không truy vấn DB trong vòng lặp tính
- [ ] Không tự commit, không tự push
