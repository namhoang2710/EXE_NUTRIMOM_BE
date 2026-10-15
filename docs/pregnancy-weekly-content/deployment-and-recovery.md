# Thiết kế xuất bản, triển khai và phục hồi (chưa thực hiện)

> Trạng thái: **chỉ là thiết kế**. Chưa có migration xuất bản trong `db/migration` vì chưa có gói nội dung
> đã duyệt. Không thêm file SQL rỗng hoặc chứa placeholder vào Flyway.

## 1. Chọn version migration

- Ngày 15/10/2026, DB phát triển dùng chung đã chạy tới **V51** (V48–V51 chạy ngày 14–15/10), trong khi
  `origin/main` chỉ có tới **V41**. Các V42–V51 không có trên nhánh remote nào → đang nằm ở nhánh Admin
  chưa push (theo task: `feat/admin-operations-dashboard`).
- Vì vậy migration xuất bản phải có version **lớn hơn version cao nhất đã áp dụng ở mọi môi trường đích tại
  thời điểm tạo** (hiện tối thiểu V52) và phải chốt với người giữ nhánh Admin để không trùng số.
- Trước khi tạo: đọc lại `flyway_schema_history` của môi trường đích và các PR đang chờ merge.
  Không `flyway repair`, không sửa V7/V22 hay migration đã chạy.

## 2. Nội dung migration (khi có gói APPROVED đã qua validator)

1. Tạo từ đúng file đã validate (ghi `dataset_id` + `sha256` trong comment đầu file). SQL được sinh bằng
   công cụ có escape đúng (dollar-quoting `$c$...$c$` của PostgreSQL), không nối chuỗi tay.
2. Đưa dữ liệu vào bảng tạm `pg_temp`, rồi kiểm tra trong cùng transaction:
   - đúng tập tuần mục tiêu, không trùng;
   - với mỗi tuần đang có: `content_version` đích **lớn hơn** bản hiện tại → update; **bằng** và nội dung
     giống → giữ nguyên (không đổi `updated_at`); bằng nhưng khác nội dung hoặc **nhỏ hơn** → `RAISE EXCEPTION`
     (toàn bộ migration rollback, không xuất bản nửa gói).
3. `UPDATE ... FROM pg_temp` theo khóa `week` cho các tuần hợp lệ; `INSERT` cho tuần chưa có. Giữ `created_at`,
   đặt `updated_at = now()` chỉ khi thực sự đổi. `review_status = 'REVIEWED'` chỉ ghi cùng lúc với đầy đủ
   nội dung + `reviewed_by` + `reviewed_at` của chính bản đó.
4. Hậu kiểm trong migration: full → đúng 43 tuần publishable; partial → đúng tập đã chốt, tuần ngoài tập
   không đổi. Sai thì `RAISE EXCEPTION`.
5. Không chạm bảng thai kỳ của người dùng.

## 3. Preflight trước khi cập nhật DB dùng chung

1. Nhóm xác nhận môi trường (dev dùng chung / staging / production) và người được phép triển khai.
2. Chạy validator với `--published=<export hiện tại>` trên đúng file sẽ dùng; lưu báo cáo.
3. Chạy migration trên **PostgreSQL riêng** đã restore schema giống môi trường đích; chạy Flyway lần 2 để
   xác nhận không thay đổi gì thêm.
4. Backup giới hạn: export 43 dòng `app.pregnancy_week_contents` (đủ mọi cột) ra file nội bộ, kiểm tra đọc
   lại được và đủ cột. Ghi số dòng, version trước/sau theo tuần.
5. Thứ tự: deploy code đã merge (đọc được cả placeholder lẫn bài mới) → chạy migration → smoke test.
6. Cache: BE hiện không cache nội dung tuần; FE/CDN kiểm tra riêng nếu có.

## 4. Khi có sự cố

| Tình huống | Xử lý |
| --- | --- |
| Migration lỗi trước khi chạy | Sửa build/config, chạy lại theo quy trình |
| Migration fail giữa chừng | PostgreSQL rollback toàn bộ; kiểm tra `flyway_schema_history` có dòng `success = false` không; điều tra, không repair để né |
| Code lỗi sau khi migrate | Rollback ứng dụng (schema không đổi nên tương thích) |
| Nội dung xuất bản sai | Migration mới sửa đúng các tuần sai (tăng version) hoặc thu hồi: đặt lại `review_status = 'UNREVIEWED'` cho đúng tuần/version đã ghi → API trả 409, dashboard `baby_summary = null`. Không xóa bảng |
| Phục hồi từ backup | Chỉ khôi phục dòng còn đúng version/hash mà lần triển khai này ghi; nếu đã có cập nhật mới hơn thì dừng và đối chiếu |

Không xóa lịch sử Flyway để chạy lại migration; không rollback dữ liệu Admin/consultation hay thai kỳ người dùng.
