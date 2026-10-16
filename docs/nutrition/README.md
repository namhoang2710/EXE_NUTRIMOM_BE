# Module dinh dưỡng mẹ bầu — bộ tài liệu thi công

Bộ này tồn tại để **mỗi nhánh làm được ở một session riêng mà không phải đọc lại file đặc tả PDF
29 trang**. Mọi công thức, bảng, endpoint và test case cần thiết đã được chép thẳng vào file nhánh
tương ứng.

## Cách dùng — dán đúng 3 dòng này vào session mới

```
Làm nhánh <N> của module dinh dưỡng NutriMom.
Đọc docs/nutrition/_CONTRACT.md rồi docs/nutrition/branch-<NN>-<tên>.md, làm đúng phạm vi trong đó.
Không mở file PDF đặc tả trừ khi file nhánh chỉ đích danh một mục.
```

Session sẽ đọc 2 file nhỏ (~400 dòng) thay vì 29 trang PDF + 12 hình.

## Thứ tự nhánh

Cột **Cắt từ** là nhánh phải merge xong trước. Hai nhánh cùng mức thụt lề chạy song song được.

| # | File | Nhánh git | Cắt từ | Migration |
|---|---|---|---|---|
| 1 | [branch-01-catalog-seed.md](branch-01-catalog-seed.md) | `feature/nutrition-catalog-seed` | `main` | **V57** |
| 2 | [branch-02-calculator.md](branch-02-calculator.md) | `feature/nutrition-calculator` | 1 | — |
| 3 | [branch-03-profile.md](branch-03-profile.md) | `feature/nutrition-profile` | 1 | **V58** |
| 4 | [branch-04-recommendation.md](branch-04-recommendation.md) | `feature/nutrition-recommendation` | 2 + 3 | lấy lúc bắt đầu |
| 5 | [branch-05-plan-generation.md](branch-05-plan-generation.md) | `feature/meal-plan-generation` | 4 | lấy lúc bắt đầu |
| 6 | [branch-06-plan-edit.md](branch-06-plan-edit.md) | `feature/meal-plan-edit` | 5 | — |
| 7 | [branch-07-shopping-list.md](branch-07-shopping-list.md) | `feature/nutrition-shopping-list` | 6 | lấy lúc bắt đầu |
| 8 | [branch-08-diary.md](branch-08-diary.md) | `feature/nutrition-diary` | 5 | lấy lúc bắt đầu |
| 9 | [branch-09-multiday.md](branch-09-multiday.md) | `feature/meal-plan-multiday` | 6 | — |
| 10 | [branch-10-revalidation.md](branch-10-revalidation.md) | `feature/nutrition-profile-revalidation` | 3 + 5 + 8 | — |

Song song được: **2 ∥ 3** · **7 ∥ 8** · **9 ∥ 10**.
Không bao giờ mở cùng lúc: **6 và 7** (cùng ghi vào plan service và shopping list version).

### Frontend

Tài liệu FE nằm **trong repo frontend**, không ở đây: `EXE_NUTRIMOM_FE/docs/nutrition/`
(`_CONTRACT.md` bản rút gọn cho FE, `fe-01-onboarding.md`, `fe-02-plan-today.md`,
`fe-03-shopping-diary.md`). Base nhánh là **`dev`**.

| Nhánh git | Đi sau BE |
|---|---|
| `feature/nutrition-onboarding` | 3 |
| `feature/nutrition-plan-today` | 6 |
| `feature/nutrition-shopping-diary` | 7 + 8 |

Session FE thỉnh thoảng cần mở `branch-NN-*.md` ở đây để biết chính xác shape của response.

## Ba file nền

| File | Khi nào đọc |
|---|---|
| [_CONTRACT.md](_CONTRACT.md) | **Mọi session.** Convention repo, Flyway, ErrorCode, 5 bất biến |
| [DECISIONS.md](DECISIONS.md) | Khi file nhánh nhắc tới một QĐ-xx cụ thể |
| `Dac_ta_tinh_nang_dinh_duong_me_bau_v2.pdf` | Gần như không bao giờ. Chỉ khi file nhánh chỉ đích danh một mục |

PDF nằm ở `C:\Users\Admin\Downloads\`. Máy **không có `pdftoppm`** — nếu buộc phải đọc thì dùng
`pdftotext -layout`.

## Việc chạy song song, không thuộc nhánh nào

**Chuẩn hóa 40–60 món Việt Nam** cho seed: gram từng nguyên liệu, trạng thái sống/chín, vi chất có
dẫn nguồn, giá ước tính. Đây là nhập liệu, không phải code. Nhánh 1 chỉ cần 8–10 món để chạy được;
nhưng **nhánh 5 không demo ra hồn nếu seed chưa đủ**, nên bắt đầu từ tuần đầu.
