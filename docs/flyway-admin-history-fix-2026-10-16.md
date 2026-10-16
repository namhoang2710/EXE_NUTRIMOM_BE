# Sửa lịch sử migration Admin — 16/10/2026

Nhánh `fix/flyway-admin-migration-history`, tách từ `origin/main` tại `0fd42c6`, sau đó đồng bộ nền lên `d1df637` trước khi chuẩn bị push theo yêu cầu. Không repair hoặc migrate DB dùng chung trong đợt sửa này.

## Kết luận đối chiếu

Khôi phục SQL từ lịch sử chỉnh sửa cục bộ, rồi tính checksum bằng chính Flyway và đối chiếu với `app.flyway_schema_history` của DB đang cấu hình. Kết nối chẩn đoán dùng transaction/session bắt buộc read-only. Không dựng lại SQL bằng cách đoán schema hoặc sửa checksum đã lưu.

| Version | Checksum DB và SQL đã khôi phục | Phần khác trong source trước sửa |
| --- | ---: | --- |
| V42 | -366517247 | Index `ix_users_admin_created` được thêm sau lần áp dụng |
| V46 | 1540402008 | Điều kiện overlap, đối chiếu XID, conflict target và trigger reconciliation được chỉnh sau lần áp dụng |
| V51 | 1932212754 | Index lịch KPI được bỏ khỏi file sau lần áp dụng |
| V53 | 66632425 | Cột tracking/finalization, constraint chặt hơn, index và trigger monitor được bổ sung sau lần áp dụng |

Cả bốn file hiện khớp checksum DB. Đây là thay đổi SQL thực, không chỉ comment/format. Không có câu lệnh sửa `flyway_schema_history` hoặc `flyway repair` trong bản sửa.

## Migration mới V60 (nay là V62)

> **Đổi số ngày 16/10/2026:** PR #45 (meal plan) cũng dùng V60 (`V60__meal_plans.sql`) và đã chạy trên DB dùng chung trước. File reconcile chưa chạy ở đâu nên được đổi thành `V62__reconcile_admin_migration_history.sql`, nội dung giữ nguyên. Các chỗ nhắc V60 bên dưới là tên cũ của file này.

V59 đã xuất hiện trong lịch sử DB dưới tên `V59__recommendation_logs.sql`, nên không lấy lại số V59 cho Admin. Ban đầu source V59 chưa có trong `main`; trước khi push đã đồng bộ `main` sau PR #42 và xác nhận checksum V59 là `1564917947`, khớp DB. Nhánh fix hiện mang đủ chuỗi V59 → V60.

`V60__reconcile_admin_migration_history.sql` đưa các thay đổi cần giữ sang bước nâng cấp mới:

- Bổ sung index người dùng; bỏ index KPI trùng mục đích.
- Siết cặp `scheduled_starts_at`/`scheduled_ends_at`: cả hai cùng null, hoặc cả hai có giá trị và bắt đầu trước kết thúc.
- Cập nhật function ghi nhận doanh thu: overlap theo toàn khoảng thời gian, XID wraparound và conflict target rõ ràng.
- Bổ sung reconciliation cho writer flush subscription trước order và invalidation revision cho monitor events; không tạo trigger trùng khi object đã có.
- Không chạy lại baseline/backfill doanh thu của V46, không phát lại notification, không tạo receipt hoặc lịch sử tham gia giả.
- Không thêm lại hai cột và index đã được V54 bổ sung.

Nếu có lịch cũ vi phạm cặp timestamp, V60 thất bại và rollback toàn bộ; không tự sửa dữ liệu lịch. Chẩn đoán chỉ đọc DB đang dùng hiện ghi nhận **0** lịch vi phạm điều kiện này.

## Kiểm chứng

- Maven `verify` toàn repository sau khi đồng bộ `main` tại `d1df637`, trên bản sao QA không có `.env`: **789 test được ghi nhận; 707 thực thi đạt, 0 failure, 0 error, 82 skip** theo cờ/live test.
- Hồi quy cuối của bản sửa: **11 test thực thi đạt, 0 failure/error/skip**, gồm 7 PostgreSQL history/upgrade, 1 checksum, 2 V54 upgrade và 1 notifications migration. Có chạy lại checksum đã nằm trong lượt toàn repo, không cộng các lượt thành tổng số test duy nhất.
- Bao gồm: tạo DB mới; nâng cấp V59 → V60 giữ nguyên đơn/gói/thông báo/trạng thái đọc/monitor/receipt/revision; object đã có không tạo trùng; lịch sai rollback nguyên vẹn; revision monitor; writer subscription trước order không tính doanh thu hai lần; hai subscription overlap với ngày bắt đầu khác nhau chỉ ghi nhận một lượt.
- `Flyway validate` trên DB QA sau migrate: đạt; migrate lần hai không thực thi lại.
- Java lint `javac -Xlint:all -Werror -proc:none -encoding UTF-8` cho 4 file test thêm/sửa: đạt. Không sửa Java production trong đợt này.
- Build JAR QA và `git diff --check`: đạt. Test checksum mới khóa bốn file lịch sử để phát hiện việc sửa lại về sau.
- Hai bài migration cũ được cố định phạm vi đến V56; fixture V53 đầy đủ được tạo tường minh. Không để việc thêm module mới làm thay đổi số migration của bài kiểm tra rollout cũ.
- Không dựng lại hoặc clean `admin_qa` cũ. Fixture dùng DB mới, có guard URL chính xác: `jdbc:postgresql://127.0.0.1:55439/admin_migration_history`; hai suite cũ tiếp tục dùng các DB fixture riêng đã có guard.

## Những điểm chưa thể chốt cho DB dùng chung

Đối chiếu toàn chuỗi ban đầu phát hiện hai vấn đề ngoài bốn migration Admin:

1. **V32__assistant.sql**: DB lưu `598666640`, source `main` hiện là `570677793`. Lịch sử Git cho thấy file này có từ trước nhánh Admin; đợt này chưa sửa V32 vì chưa xác minh được đúng SQL đã chạy.
2. **V59__recommendation_logs.sql**: đã giải quyết bằng cách đồng bộ đúng source từ `main` sau PR #42; checksum `1564917947` khớp DB. Không dùng placeholder hoặc bỏ qua missing migration.

V60 là migration mới nên chưa được áp dụng trên DB dùng chung. Khi kiểm tra trước triển khai có thể bỏ qua trạng thái `pending` của migration mới, nhưng vẫn phải xử lý checksum V32. Không đổi cấu hình runtime để bỏ qua lỗi checksum/missing migration.

Lượt đối chiếu chỉ đọc cuối trước push xác nhận bốn checksum Admin và V59 đều khớp; `validateWithResult` chỉ còn báo `CHECKSUM_MISMATCH` cho V32 khi bỏ qua migration mới đang pending. Transaction chẩn đoán được xác nhận `transaction_read_only=on`.

**Bốn lỗi Admin đã sửa và V59 đã đồng bộ; chưa tuyên bố toàn bộ DB dùng chung validate sạch hoặc sẵn sàng triển khai do V32 còn lệch.** Cần lấy đúng bản V32 để đối chiếu trước khi deploy. Source/contract FE không đổi.

Log cục bộ ngoài Git: `D:\KI8\.tmp\flyway-history-prepush-full-verify.log`, `flyway-history-prepush-regression.log`, `flyway-history-prepush-lint.log`, `flyway-shared-prepush-readonly.log`. Bản sao build QA: `D:\KI8\.tmp\flyway-admin-history-qa`.

## Cập nhật V32 (16/10/2026)

V32 đã được xử lý trên nhánh `fix/flyway-v32-assistant-history`:

- Bản đã chạy trên DB = bản trên `main` bỏ đúng dòng `CREATE INDEX ix_assistant_conversations_expiry ...`. Bằng chứng: checksum Flyway ra đúng `598666640` như DB, và DB dùng chung không có index này (chỉ có `assistant_conversations_pkey`, `ix_assistant_conversations_owner`).
- `V32__assistant.sql` được trả về bản đã chạy; `AssistantMigrationChecksumTest` khoá checksum `598666640`.
- Index được tạo lại bằng `V61__assistant_conversation_expiry_index.sql` (`CREATE INDEX IF NOT EXISTS`), để DB tạo mới và DB dùng chung có cùng schema.
- Thử trên PostgreSQL riêng: migrate tới V59 (chưa có index) → migrate tiếp chạy V60, V61 (có index) → chạy lại không áp dụng gì → `validate` OK.
- Không ghi gì vào DB dùng chung; V60 và V61 sẽ chạy ở lần khởi động BE kế tiếp nối vào DB đó.

## Cập nhật trùng V60 (16/10/2026)

- `main` từng có hai file version 60 (`V60__meal_plans.sql` và `V60__reconcile_admin_migration_history.sql`); Flyway dừng khởi động vì trùng version.
- DB dùng chung đã chạy V60 = `meal_plans` (checksum khớp source). File reconcile đổi thành **V62**; V61 (index trợ lý) giữ nguyên.
- `AdminMigrationHistoryPostgresTest` cập nhật theo trạng thái thật: DB ở V60, nâng cấp chạy 2 migration (V61, V62), version cuối là 62.
