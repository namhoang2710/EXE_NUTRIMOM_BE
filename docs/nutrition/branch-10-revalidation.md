# Nhánh 10 — Cập nhật hồ sơ và kiểm tra lại kế hoạch

> Đọc trước: [_CONTRACT.md](_CONTRACT.md).

**Mục tiêu:** đổi hồ sơ thì các mục **tương lai** được kiểm tra lại, còn **lịch sử đã ăn giữ nguyên**.
Nhánh cross-cutting, đụng vào hồ sơ, kế hoạch và nhật ký cùng lúc — làm **sau cùng**.

| | |
|---|---|
| Nhánh git | `feature/nutrition-profile-revalidation` |
| Cắt từ | nhánh 3 **và** 5 **và** 8 |
| Migration | không có |
| Demo được | Thêm dị ứng mới → các bữa tương lai bị đánh dấu, bữa đã ăn không đổi |

Chạy song song được với nhánh 9.

---

## 1. UC06 — ba mức ảnh hưởng, đừng xử lý như nhau

| Loại thay đổi | Ảnh hưởng |
|---|---|
| **Dị ứng và giới hạn bắt buộc** | Kiểm tra các mục **hôm nay chưa ăn** và **mọi ngày tương lai**. Đánh dấu mục xung đột |
| **Sở thích hoặc ngân sách** | Chỉ ảnh hưởng **lần gợi ý tiếp theo**. Cho nút "Áp dụng cho thực đơn hiện tại" — người dùng tự bấm |
| **Ngày đã ăn** | **Không đụng tới.** Giữ lịch sử và ghi chú về hồ sơ tại thời điểm nhập |

**BR17: thay đổi hồ sơ không viết lại lịch sử đã ăn.**

Lưu hồ sơ làm **tăng `profile_version`**.

---

## 2. Vòng trạng thái

```
PREVIEW ──lưu──▶ SAVED ──hồ sơ đổi──▶ NEEDS_UPDATE
                   ▲                        │
                   └────đổi mục xung đột────┘
```

- `NEEDS_UPDATE` **không** chặn xem kế hoạch, chỉ đánh dấu mục cần đổi.
- Đổi hết mục xung đột → quay về `SAVED`.
- **BR10: món khóa vẫn được kiểm tra lại khi hồ sơ thay đổi.** Mục khóa không còn hợp lệ → báo mục đó
  cần đổi, **không âm thầm mở khóa**, không tự thay món.

---

## 3. Bảng tình huống ngoại lệ — hiện thực đủ cả bảng

| Tình huống | Hành vi phải có |
|---|---|
| Chưa có kế hoạch | Bữa gợi ý và nút tạo hôm nay; **không** chỉ một màn hình trắng |
| Chưa rõ dị ứng | Cho xem nội dung; nhắc xác nhận **trước khi** lưu cá nhân hóa |
| Không còn món sau lọc | Cho sửa các **ưu tiên mềm**; **không** bỏ điều kiện dị ứng |
| Có món đã ăn | Không tạo lại nhật ký; **không đổi món đã ăn** trong luồng tạo lại |
| Khẩu vị tạm thời do khó ăn | Giảm ưu tiên **có thời hạn**, không biến thành dị ứng |
| **Thêm dị ứng mới** | Đánh dấu các mục tương lai xung đột; yêu cầu đổi trước khi tiếp tục dùng kế hoạch |
| Ngân sách dự kiến bị vượt | Nêu mức vượt; cho chọn rẻ hơn hoặc thay ngân sách |
| Giá chưa có | Hiển thị "Chưa có giá"; tổng chi phí kèm nhãn chưa đầy đủ |
| Dinh dưỡng thiếu | Tổng phần đã biết + nhãn chưa đủ dữ liệu; **không tự điền 0** |
| Hai thiết bị cùng đổi món | Phát hiện `expected_version` cũ; **không ghi đè âm thầm** |
| Gửi ý ăn hai lần vì mạng chậm | `request_id` duy nhất trả **cùng bản ghi**, không tạo bản thứ hai |
| Món ngoài bộ dữ liệu | Lưu ghi chú và lượng; **không tự sinh vi chất từ tên món** |
| Thực đơn qua ngày | Mở đúng ngày; bữa chưa ghi **không tự thành đã ăn** |
| Hết hạn preview | Cho tạo lại và **giữ lựa chọn còn hợp lệ** nếu có thể |

Thông báo cần **ngắn, nói được thao tác tiếp theo, và giữ các dữ liệu hợp lệ đã nhập**.

---

## 4. Giọng điệu thông báo

Dùng lời nhắc cụ thể: "Bạn còn một bữa chưa ghi", "Hôm nay có thể thêm một món rau".

**Không** dùng thông báo gây áp lực kiểu "Bạn thất bại" hoặc "Bạn thiếu chất" chỉ dựa vào một ngày
nhật ký.

**BR20:** không có mục tiêu năng lượng thì **không hiển thị tỉ lệ đạt mục tiêu năng lượng**.

---

## 5. Endpoint

Không thêm endpoint mới. Mở rộng:

- `PUT /api/v1/me/nutrition-profile` → trả thêm **danh sách mục tương lai cần kiểm tra** (nhánh 3 trả
  rỗng, nhánh này trả thật).
- `GET /api/v1/meal-plans/{id}` → trả `lifecycle_state` và cờ xung đột ở từng mục.
- Thêm nút "Áp dụng cho thực đơn hiện tại" → dùng lại `POST .../regenerate` của nhánh 6.

---

## Test bắt buộc

| TC | Dữ liệu | Kết quả mong đợi |
|---|---|---|
| TC12 | Món **giữ** vi phạm dị ứng mới | Báo **cần đổi**; không tự mở khóa, không tự giả định hợp lệ |
| — | Thêm dị ứng mới | Mục tương lai bị đánh dấu; mục **đã ăn** không đổi |
| — | Đổi sở thích | Chỉ ảnh hưởng gợi ý sau; kế hoạch hiện tại giữ nguyên tới khi bấm áp dụng |
| — | Đổi hết mục xung đột | `lifecycle_state` quay về `SAVED` |
| — | Ngày đã ăn sau khi đổi hồ sơ | Lịch sử và ghi chú hồ sơ tại thời điểm nhập **không bị sửa** |

---

## KHÔNG làm trong nhánh này

Tự đổi món thay người dùng · tự mở khóa mục đã khóa · sửa lịch sử nhật ký · nới lỏng điều kiện dị ứng.

---

## Xong là khi

- [ ] Năm TC trên có test, xanh
- [ ] **Toàn bộ** bảng tình huống ở mục 3 có hành vi tương ứng, có test cho ít nhất các dòng in đậm
- [ ] Không có đường nào sửa được bản ghi của ngày đã ăn
- [ ] Cập nhật `docs/NUTRITION_API.md`
- [ ] Không tự commit, không tự push
