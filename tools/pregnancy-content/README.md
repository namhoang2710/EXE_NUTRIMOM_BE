# Công cụ kiểm tra bộ nội dung tuần thai

Validator chỉ **đọc file**, không bao giờ ghi DB. Mã nguồn:
`src/main/java/vn/nutrimom/pregnancy/content/PregnancyContentDatasetValidator.java`
(CLI: `PregnancyContentValidatorCli`). Quy tắc chi tiết: `docs/pregnancy-weekly-content/content-format.md`.

## Chạy (PowerShell, tại thư mục BE)

```powershell
# 1. Biên dịch và lấy classpath một lần
.\mvnw.cmd -q compile dependency:build-classpath "-Dmdep.outputFile=target/cp.txt" "-Dmdep.includeScope=runtime"
$cp = "target/classes;" + (Get-Content target/cp.txt -Raw).Trim()

# 2. Kiểm tra đủ 43 tuần (mặc định --mode=full)
java -cp $cp vn.nutrimom.pregnancy.content.PregnancyContentValidatorCli duong-dan/bo-noi-dung.json --report=target/content-validation-report.json

# Gói xuất bản một phần: bắt buộc nêu danh sách tuần đã chốt
java -cp $cp vn.nutrimom.pregnancy.content.PregnancyContentValidatorCli goi-tuan-10-20.json --mode=partial --weeks=10-20

# So với bản đang xuất bản (cùng định dạng JSON) để chặn lùi version / sửa nội dung mà không tăng version
java -cp $cp vn.nutrimom.pregnancy.content.PregnancyContentValidatorCli bo-moi.json --published=ban-dang-xuat-ban.json
```

## Đầu ra

- stdout: báo cáo JSON gồm `dataset_id`, `sha256` của file, `checked_at`, `mode`, `target_weeks`,
  `valid_weeks`, `invalid_weeks`, `missing_weeks`, `errors[]` và `warnings[]` (mỗi lỗi có `week`, `field`, `code`, `message`).
- stderr: một dòng tóm tắt `PUBLISHABLE` hoặc `BLOCKED`.
- `--report=<file>`: ghi thêm báo cáo ra file.

| Mã thoát | Ý nghĩa |
| --- | --- |
| 0 | Gói đạt mọi quy tắc, được phép chuyển sang bước tạo migration |
| 1 | Có lỗi chặn xuất bản; toàn bộ gói bị chặn |
| 2 | Sai tham số hoặc không đọc/ghi được file |

## Mã lỗi

`INVALID_JSON`, `INVALID_DATASET`, `INVALID_TYPE`, `MISSING_WEEK`, `DUPLICATE_WEEK`, `OUT_OF_SCOPE_WEEK`,
`EMPTY_FIELD`, `PLACEHOLDER_TEXT`, `TOO_LONG`, `INVALID_SOURCE`, `UNAPPROVED_CONTENT`, `INVALID_RANGE`,
`VERSION_CONFLICT`. Cảnh báo (không chặn): `UNKNOWN_FIELD`, `REVIEW_OVERDUE`.
Đây là mã của công cụ, không phải mã lỗi của public API.

## Giới hạn

- Chỉ đọc JSON theo `content-template.json`. Bộ bài ở Word/Excel/Sheets cần chuyển sang JSON trước;
  người chủ nội dung xác nhận bản chuyển đổi khớp bản đã duyệt.
- Phát hiện placeholder chỉ dựa trên vài mẫu chữ (TODO, “chờ thẩm định”…); không thay thế duyệt chuyên môn.
- Đạt validator **không** chứng minh nội dung đúng về y khoa.
- Test: `PregnancyContentDatasetValidatorTest` (dữ liệu tổng hợp, không phải bài thật).
