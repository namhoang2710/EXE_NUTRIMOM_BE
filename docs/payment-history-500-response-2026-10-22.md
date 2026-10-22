# Phản hồi FE — HTTP 500 của API lịch sử giao dịch

Ngày: 22/10/2026
Trả lời: `NutriMom_BE_Payment_History_500_Fix.md` mục 7.
Endpoint: `GET /api/v1/payments/orders`.

## 1. Nguyên nhân đã xác nhận

Chẩn đoán của FE đúng. Thủ phạm là `PaymentOrderRepository.findHistory`:

```
and (:from is null or p.createdAt >= :from)
and (:to is null or p.createdAt < :to)
```

Khi FE không gửi ngày, `PaymentService.getUserOrders` truyền `from = null`, `to = null`. Hibernate
bind null bằng `PreparedStatement.setNull(idx, Types.TIMESTAMP)` hoặc
`Types.TIMESTAMP_WITH_TIMEZONE`. Trong `postgresql-42.7.11` — đúng phiên bản BE đang build —
`PgPreparedStatement.setNull` ánh xạ cả bốn mã thời gian (92 TIME, 93 TIMESTAMP, 2013, 2014) sang
**oid 0 = UNSPECIFIED**, trong khi 91 DATE ra oid 1082 và 12 VARCHAR ra oid 1043. Tham số không có
oid nằm một mình trong vế `$1 IS NULL` thì Postgres không còn chỗ nào để suy kiểu, và trả
`SQLSTATE 42P18 could not determine data type of parameter $1`.

Hệ quả đúng như FE quan sát:

- Lỗi xảy ra với **mọi** request không gửi đủ cả `fromDate` lẫn `toDate`, không liên quan tới giá trị
  `status=PAID`. Endpoint này chưa từng chạy được trên PostgreSQL.
- Các bộ lọc enum và chuỗi không dính vì chúng có oid thật. Đây cũng là lý do
  `ConsultationRequestRepository` dùng cùng mẫu `:param is null` mà vẫn chạy: tham số của nó là
  `LocalDate`.

## 2. Cách sửa

Commit `a18dd2a` — `fix(payment): stop the transaction history 500 on PostgreSQL`, nhánh
`fix/payment-history-null-date-params`, đã merge vào `main` qua PR #56.

Bỏ hẳn mẫu `:param is null`, dựng truy vấn theo đúng bộ lọc có giá trị — chính là phương án ưu tiên
FE đề nghị:

- `PaymentOrderRepository` bỏ `findHistory` và nhận thêm `JpaSpecificationExecutor`.
- `PaymentService.historyFilter(...)` chỉ thêm predicate cho bộ lọc khác null. `userId` luôn lấy từ
  JWT và luôn nằm trong truy vấn.
- Thứ tự `created_at DESC, id DESC` chuyển vào `Pageable` nên không đổi, và vẫn dùng index
  `ix_payment_orders_user_created`.
- Specification tự sinh count query cùng tập điều kiện, nên trang và tổng số luôn khớp nhau.

## 3. Kiểm chứng

SQL thật Hibernate sinh ra sau khi sửa:

| Request | SQL |
|---|---|
| không bộ lọc | `where user_id=? order by created_at desc, id desc` |
| `status=PAID` | `where user_id=? and status=?` |
| chỉ `planTier` | `where user_id=? and plan_tier=?` |
| chỉ `fromDate` | `where user_id=? and created_at>=?` |
| cả hai ngày | `where user_id=? and created_at>=? and created_at<?` |
| count | `select count(id) ... where user_id=?` |

Không còn `is null` ở đâu, và khi không có bộ lọc ngày thì **không sinh placeholder thời gian nào**,
nên không còn chỗ cho 42P18.

Regression trên PostgreSQL thật: `PaymentHistoryPostgresTest` phủ toàn bộ ma trận ở mục 6 tài liệu
FE (không bộ lọc, từng bộ lọc một, tổ hợp, không khớp gì, phân trang khi trùng `created_at`, hai
user). Bài này opt-in và khoá URL vào DB QA rời, không bao giờ chạm DB ứng dụng:

```bash
./mvnw test -Dtest=PaymentHistoryPostgresTest -Dpayment.compatibility.test=true -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55439/admin_qa -Dspring.datasource.username=adminqa -Dspring.datasource.password= -Dspring.datasource.driver-class-name=org.postgresql.Driver -Dspring.jpa.hibernate.ddl-auto=validate -Dspring.flyway.enabled=false
```

`PaymentIntegrationTest` (H2) vẫn xanh, chứng minh contract không đổi. Lưu ý để FE hiểu đúng giá trị
của nó: **H2 chính là chỗ đã che bug này** — nó không ép suy luận kiểu tham số nên bộ test H2 xanh
suốt trong khi production chết. Test H2 chỉ là bằng chứng về contract, không phải về SQL.

## 4. Chỗ chưa trả lời được

Mục 7.1 hỏi log của request 500 thật kèm `request_id` và SQLSTATE. BE không truy lại được log của
lần đó, nên bằng chứng ở đây là cơ chế chứ không phải vết chạy: ánh xạ oid của driver, SQL sinh ra
trước và sau khi sửa, và bài regression trên PostgreSQL. Nếu sau khi triển khai vẫn còn 500 trên
endpoint này thì đó là lỗi khác và cần điều tra theo log thực tế, không mặc định là lỗi cũ.

## 5. Contract

Không đổi gì: query camelCase `page`, `pageSize`, `status`, `planTier`, `fromDate`, `toDate`; body
snake_case `items`, `page`, `page_size`, `total_items`, `total_pages`; lọc theo `created_at` với ngày
lịch UTC, `toDate` bao trọn ngày bằng cận trên loại trừ; chỉ trả đơn của chính người gọi; không có
kết quả thì 200 với trang rỗng. FE không phải đổi gì và không cần gửi ngày giả.

## 6. Môi trường

Mã đã nằm trên `main`. Phiên bản đang chạy ở môi trường tích hợp: _(điền khi deploy)_.
Sau khi deploy, gọi lại `GET /api/v1/payments/orders?page=1&pageSize=20&status=PAID` cùng ba biến thể
không bộ lọc / chỉ `fromDate` / cả hai ngày là đủ để FE xác nhận.
