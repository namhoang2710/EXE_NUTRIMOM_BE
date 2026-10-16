# Nhánh 4 — Lọc điều kiện và xếp hạng

> Đọc trước: [_CONTRACT.md](_CONTRACT.md).

**Mục tiêu:** gợi ý món **có lý do giải thích được**. Đây là phần "chứng minh" của đồ án — mọi con số
trong file này phải tái lập được bằng fixture.

| | |
|---|---|
| Nhánh git | `feature/nutrition-recommendation` |
| Cắt từ | nhánh 2 **và** nhánh 3 (cần cả hai đã merge) |
| Migration | **V44** (`app.recommendation_logs`) |
| Demo được | Gọi API trả danh sách món kèm lý do, đổi hồ sơ thì danh sách đổi theo |

---

## 1. Pipeline — nối tiếp, không song song

```
Hồ sơ + dữ liệu → EligibilityFilter → Ranker → (nhánh 5: PlanGenerator → Validator)
                                        ↑
                          Nhật ký và phản hồi (nhánh 8)
```

**Bất biến 2:** món chưa qua lọc **không bao giờ** được chấm điểm.

---

## 2. EligibilityFilter

Nhận `recipe_version` và `profile_version`. Trả `ELIGIBLE` | `BLOCKED` | `UNKNOWN` kèm `reason_codes`.

Kiểm tra: **nguyên liệu con** (gia vị, nước dùng hỗn hợp) · dị ứng · giới hạn chế độ ăn ·
điều kiện chế biến được yêu cầu.

| Quy tắc | Nội dung |
|---|---|
| BR02 | Lọc ở **mức nguyên liệu và nguyên liệu con**. Nước dùng chứa tôm khô phải chặn món khi người dùng dị ứng tôm |
| BR03 | Món **chưa rõ thành phần** bắt buộc → không vào thực đơn cá nhân hóa. Vẫn nằm trong nội dung tra cứu |
| BR04 | Chỉ `ELIGIBLE` mới đi vào danh sách cá nhân hóa |
| BR05 | Sở thích và điểm số **không được vượt qua** dị ứng |

Yêu thích hoặc khóa món **không bypass** bước này.

**Lọc theo loại bữa và vai trò trước khi chấm điểm.** Món ăn kèm không tranh thứ hạng với món hoàn
chỉnh để tạo bữa.

**BR13:** thời gian và chi phí **mặc định là ưu tiên mềm**, chỉ trở thành bộ lọc cứng khi người dùng
bật giới hạn cứng (`strict_time`, `strict_budget`).

---

## 3. Ranker — công thức điểm

```
S = 0,35·N + 0,30·P + 0,15·V + 0,10·B + 0,10·C
```

Mỗi thành phần nằm trong **0–100**. Đây là cấu hình thử nghiệm sản phẩm, **không phải công thức y khoa**.

### N — Dinh dưỡng

Với mỗi chất k **có dữ liệu** cho khẩu phần và `t_k > 0`:

```
d_k     = min(q_k / t_k, 1)
N_micro = 100 × Σ(ω_k · d_k) / Σ(ω_k)        (chỉ trên các chất có dữ liệu)
```

`q_k` = lượng chất trong khẩu phần đang xét · `t_k` = phần mục tiêu phân bổ theo bữa và vai trò ·
`ω_k` mặc định bằng nhau trong nhóm vi chất được cấu hình.

Nếu **có** mục tiêu năng lượng:

```
E_fit = 100 × max(0, 1 − abs(e − e_slot) / e_slot)
N_raw = 0,5·E_fit + 0,5·N_micro
```

Thiếu một nhánh thì chỉ dùng nhánh có dữ liệu và **ghi lại nhánh nào đang được dùng**.

Chặn trên để dữ liệu ít không vô tình có lợi:

```
N = N_raw × coverage_ratio
coverage_ratio = số chất có dữ liệu / số chất cấu hình của nhánh tương ứng
```

`coverage_ratio` là **hệ số chất lượng dữ liệu**, không phải thay giá trị thiếu bằng 0. Mọi `q_k`
thiếu vẫn lưu `null`. Không tính được `N` → **bỏ trọng số N khỏi phép tổng** và trả
`nutrition_status = PARTIAL`. Không kết luận khẩu phần cân đối.

> Điểm N chỉ hỗ trợ chọn món, **không chứng minh** món hoặc ngày đã đủ chất.

### P — Khẩu vị

Bắt đầu ở **50**:

| Điều kiện | Điểm |
|---|---|
| Món yêu thích (`FAVORITE`) | +20 |
| Hợp nhóm ẩm thực thích | +15 |
| Hợp tags dung nạp đã chọn | +15 |
| Phản hồi gần nhất `TASTY` | +10 |
| Phản hồi gần nhất `DISLIKE` | −25 |
| Phản hồi gần nhất `HARD_NOW` | −20 (trong 7 ngày mặc định) |
| Có nguyên liệu không thích dạng mềm | −35 |

**Chỉ áp một phản hồi gần nhất** cho món; các sở thích khác vẫn tính riêng. Chặn điểm vào 0–100.

`preference_type = BLOCKED` ("Không gợi ý món này nữa") → **loại khỏi ứng viên**, không phải giảm P.

### V — Đa dạng

```
V = max(0, 100 − 40·I_recipe − 20·I_mainIngredient − 10·n_week)
```

`I_recipe` = 1 nếu món xuất hiện trong **hai ngày gần đây** · `I_mainIngredient` = 1 nếu nguyên liệu
chính xuất hiện **ngày trước** · `n_week` = số lần món đã có trong tuần đang tạo, **không tính lần
đang xét**.

Món người dùng **tự lập lịch chủ động** vẫn được giữ; chỉ phần kế hoạch còn lại tăng đa dạng.
Tag `main_ingredient` phục vụ đa dạng, **không phục vụ phát hiện dị ứng**.

### B — Chi phí và C — Thuận tiện

```
B = clamp(100 − 100 × max(0, cost / budget_slot − 1), 0, 100)
C = clamp(100 − 100 × max(0, minutes / max_minutes − 1), 0, 100)
```

`budget_slot` = ngân sách ngày × tỉ lệ bữa và vai trò trong template; **tính lại khi cấu trúc bữa
thay đổi**.

Người dùng **bỏ qua** ngân sách hoặc thời gian → **bỏ thành phần tương ứng**, không tự cho điểm 0.
**Giá chưa có cũng làm B không hoạt động** (không phải B = 0).

### Tổng khi thiếu tiêu chí

Với tập tiêu chí đang dùng `A`:

```
S = Σ(w_i · f_i) / Σ(w_i),   i ∈ A
```

Tất cả không dùng được → xếp theo **thứ tự ổn định** từ bộ món mẫu và ghi "đây là gợi ý cơ bản".

**Bằng điểm** → ưu tiên dữ liệu đầy đủ hơn, rồi `recipe_id`. Một `seed` ngẫu nhiên dùng cho nút
"Tạo lại" để tạo biến thể **có thể tái lập**.

### Ví dụ kiểm tra — phải khớp chính xác

| Món | N | P | V | B | C | S |
|---|---|---|---|---|---|---|
| A | 80 | 95 | 70 | 90 | 85 | **84,5** |
| B | 90 | 60 | 90 | 70 | 70 | **77,0** |
| C | 70 | 80 | 100 | 100 | 90 | **82,5** |

Đây là dữ liệu giả lập minh họa phép tính, **không dùng làm bằng chứng dinh dưỡng**.

---

## 4. Lý do hiển thị

**BR15: giải thích phải lấy từ dữ liệu thật**, không sinh câu chung chung, không tạo lời khẳng định
điều trị. Tối đa **hai** lý do mỗi món.

Mẫu: "Hợp khẩu vị bạn đã chọn" · "Khoảng 20 phút nấu" · "Chưa có trong thực đơn hai ngày gần đây".

**Điểm thuật toán giữ ở backend**, không biến thành điểm sức khỏe trên giao diện.

Không cần dịch vụ AI bên ngoài — lý do lấy từ `reason_codes` và mẫu câu.

---

## 5. Bảng và endpoint

`app.recommendation_logs`: `id`, `user_id`, `request_id`, `profile_version`, `rules_version`,
`candidates`, `feature_scores`, `reason_codes`.

```
POST /api/v1/recommendations    gợi ý cho một bữa, hoặc cho phần còn lại của ngày
```

**Cần đăng nhập** (khác hai endpoint `/recipes` công khai ở nhánh 1).

---

## Test bắt buộc

| TC | Dữ liệu | Kết quả mong đợi |
|---|---|---|
| TC01 | Dị ứng tôm; **nước dùng** chứa tôm khô | Món bị loại **trước** chấm điểm |
| TC02 | Món yêu thích chứa thành phần dị ứng | Yêu thích **không** làm món hợp lệ |
| TC03 | Thành phần gia vị chưa rõ | Không đưa vào thực đơn cá nhân hóa |
| TC06 | Ngân sách không nhập | B không hoạt động; trọng số còn lại **chuẩn hóa** |
| TC07 | Điểm 80 / 95 / 70 / 90 / 85 | Tổng **84,5** đúng công thức |
| — | Thiếu toàn bộ dữ liệu vi chất | Bỏ trọng số N, trả `nutrition_status = PARTIAL` |

---

## KHÔNG làm trong nhánh này

Ghép bữa và lập kế hoạch (nhánh 5) · đổi món (nhánh 6) · tự tính tổng dinh dưỡng — **gọi
NutritionCalculator của nhánh 2**.

---

## Xong là khi

- [ ] V44 chạy sạch
- [ ] Sáu TC trên có test, xanh; TC07 khớp **84,5** không sai số
- [ ] Mọi kết quả có **ít nhất một** lý do đối chiếu được với dữ liệu
- [ ] `recommendation_logs` ghi đủ `feature_scores` để tái lập xếp hạng
- [ ] Cập nhật `docs/NUTRITION_API.md`
- [ ] Không tự commit, không tự push
