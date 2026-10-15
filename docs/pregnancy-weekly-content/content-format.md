# Định dạng gói nội dung tuần thai (schema_version 1)

Mẫu để điền: `content-template.json` (43 tuần 0–42, `dataset_status = DRAFT`, nội dung trống).
Kiểm tra bằng `tools/pregnancy-content/README.md` trước khi gửi BE tạo migration.

## Phần đầu gói

| Field | Bắt buộc | Quy tắc |
| --- | --- | --- |
| `dataset_id` | Có | Định danh gói, ví dụ `pregnancy-weekly-content-2026-11` |
| `dataset_status` | Có | `DRAFT` khi đang soạn; chỉ `APPROVED` mới được xuất bản |
| `schema_version` | Có | Số nguyên `1` |
| `weeks` | Có | Mảng các tuần. Full: đúng tập 0–42. Partial: đúng tập tuần nhóm đã chốt |

`dataset_id`, `dataset_status`, `schema_version`, `approval_reference` dùng để truy vết, **không phải cột DB**.

## Mỗi tuần

| Field | Bắt buộc | Quy tắc | Cột DB |
| --- | --- | --- | --- |
| `week` | Có | Số nguyên 0–42, không trùng; `20.5` hay `"20"` bị từ chối | `week` |
| `title` | Có | Không rỗng, tối đa 200 ký tự | `title` |
| `summary`, `baby_development`, `mother_changes`, `care_tips`, `warning_signs`, `disclaimer` | Có | Chuỗi không rỗng, nội dung thật của đúng tuần đó; không TODO/“chờ thẩm định” | cùng tên |
| `sources` | Có | Mảng không rỗng; mỗi phần tử có `name` và `url` (http/https, host thật) hoặc `reference` nội bộ nhóm đã thống nhất | `sources` (TEXT, lưu JSON string một lần) |
| `reviewed_by` | Có | Người/đơn vị nhóm xác nhận được quyền duyệt, tối đa 255 ký tự | `reviewed_by` |
| `reviewed_at` | Có | Thời điểm duyệt thật, ISO 8601 có múi giờ, ví dụ `2026-11-02T09:00:00+07:00`; không phải lúc chạy migration, không ở tương lai | `reviewed_at` |
| `next_review_at` | Không | Null hoặc sau `reviewed_at`; quá hạn chỉ cảnh báo | `next_review_at` |
| `content_version` | Có | Số nguyên ≥ 1; đổi nội dung đã xuất bản phải tăng version | `content_version` |
| `approval_reference` | Có | Tham chiếu tới xác nhận duyệt đúng nội dung/phiên bản (biên bản, email, mã phiếu…) | không có cột, giữ trong gói bàn giao |
| `baby_length_cm_min`/`_max` | Không | Cùng null hoặc cùng có; số ≥ 0, tối đa 2 chữ số thập phân, ≤ 9999.99; min ≤ max | cùng tên |
| `baby_weight_g_min`/`_max` | Không | Cùng null hoặc cùng có; số nguyên ≥ 0; min ≤ max | cùng tên |
| `comparison_label` | Không | Null hoặc chuỗi không rỗng ≤ 200 ký tự, thuộc nội dung đã duyệt | `comparison_label` |

Ví dụ một nguồn (URL chỉ để minh họa cú pháp, validator sẽ từ chối `example.org`):

```json
{ "name": "Tên tài liệu hoặc cơ quan phát hành", "url": "https://example.org/path-to-source" }
```

## Quy tắc duyệt

- Duyệt một lần cho cả bộ được phép nếu có xác nhận thật cho đúng danh sách tuần và đúng `sha256` của file
  (lấy từ báo cáo validator). Duyệt tuần 10–20 không có nghĩa tuần khác đã duyệt.
- Sửa nội dung sau khi duyệt (kể cả đổi nghĩa khi làm sạch chữ) phải duyệt lại.
- Không tự chép bài tuần khác cho tuần 0–1 hay 41–42; người phụ trách nội dung quyết định.
- Không tự ước lượng số đo thai nhi; không có số liệu đã duyệt thì để null.
- AI có thể hỗ trợ soạn bản nháp nhưng không được ghi là người duyệt.
