# Kiểm chứng mốc dữ liệu và so sánh KPI — 16/10/2026

Nhánh BE: `feat/admin-operations-dashboard`; FE: `feat/admin-workspace-dashboard`.

## Kết quả

- Kết nối chỉ đọc DB đang cấu hình: 8 expert; hồ sơ đầu tiên 22/09/2026, booking/lịch tư vấn đầu tiên 28/09/2026. Không suy ngày tạo DB từ các mốc này. Với chuyên cần v3, bộ lọc bắt đầu từ mốc hồ sơ/lịch làm việc 22/09 do DB trả.
- Chạy service BE mới trên DB cấu hình trong transaction read-only: dashboard trả `filter_min_date=2026-09-22`, `comparison_status=PARTIAL_PREVIOUS_PERIOD`; báo cáo có 8 dòng và cả 8 có kỳ trước chưa đầy đủ.
- Không chạy migration, ghi dữ liệu, khởi động/restart BE hoặc thay cấu hình DB online.
- BE trả metadata phạm vi và từng dòng báo cáo, giữ số liệu thật, chặn ngày trước mốc với 422. Kỳ thiếu thời gian dữ liệu có delta null và lý do rõ ràng. CSV kèm coverage.
- FE dùng cùng metadata cho 4 lịch ngày ở Dashboard/Báo cáo; không thêm request metadata. Popup giữ số liệu và phân biệt tăng số lượt với tăng tỷ lệ.

## Kiểm thử

- Hồi quy BE: 69 kiểm thử liên quan đã thực thi và đạt, gồm 66 ở lượt chính và 3 migration được bật cờ riêng sau đó; không cộng các lượt chạy lặp.
- Bao gồm ví dụ thực tế 720/744 và 213/216, kỳ đầu 9 ngày, ngày biên 21/09 bị chặn và 22/09 được nhận; đủ tháng 28/29/30/31 ngày.
- Hài lòng 10/12 so 3/3 vẫn giảm 16,67 điểm phần trăm khi hai kỳ đủ thời gian dữ liệu; 3 sao vào mẫu số.
- Báo cáo expert mới không kế thừa cờ đủ kỳ từ nhóm có expert cũ.
- Java lint `javac -Xlint:all -Werror -proc:none`: 3 production + 2 test files đạt.
- Maven `package -DskipTests`: đạt, chạy sau hồi quy trên bản sao source không có `.env` tại `D:\KI8\.tmp\expert-kpi-be-qa` để không kích hoạt DevTools của BE đang chạy.
- FE lint, TypeScript app/tests và build: đạt; 436 unit/runtime tests; 15 Dashboard + 13 luồng User/Expert/Reports/Video browser scenarios đạt.
- DTO thực được serialize từ BE mới/Jackson/PostgreSQL QA, rollback fixture rồi đưa vào mapper FE để kiểm chứng contract.
- `git diff --check`: đạt ở hai repo. Build FE vẫn có cảnh báo kích thước chunk LiveKit có sẵn.

## Áp dụng

Không cần migration mới; schema vẫn V55/V56. Cần chạy lại BE bằng source mới để FE nhận metadata coverage vừa bổ sung. Không chỉnh dữ liệu cũ hoặc tạo lịch/đánh giá bù để làm đẹp KPI.

Contract cập nhật: [admin-expert-performance-v3-contract.vi.md](admin-expert-performance-v3-contract.vi.md).
