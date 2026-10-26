# Đối soát đống đơn PENDING tồn đọng

Trả lời `NutriMom_BE_Payment_Lifecycle_Fix.md` mục 4. Đây là **việc vận hành chạy một lần trên dữ
liệu thật**, không phải code trong nhánh.

## Vì sao vẫn cần làm tay

Job đối soát của V67 (`PaymentReconciliationJob`) chỉ nhận đơn `status = PENDING` **và**
`source = REAL`. Với đơn tạo trước V67 — không có `expires_at` — nó vẫn xử lý được, vì câu
`findDueForReconciliation` có nhánh riêng `expires_at is null and created_at < :legacyCutoff`.

Nghĩa là sau khi deploy:

| Nguồn | Job tự xử lý? |
|---|---|
| `REAL` | **Có**, kể cả đơn cũ không có hạn |
| `DEMO` | Không — link `MOCK-…` gọi PayOS sẽ 404 |
| `LEGACY_UNKNOWN` | Không — có thể thuộc writer khác trên DB dùng chung |

Hai nhóm dưới nằm lại PENDING vĩnh viễn nếu không ai đụng tới. Và nhóm `REAL` tuy tự chạy nhưng lần
quét đầu tiên sẽ gặp toàn bộ tồn đọng cùng lúc, nên phải biết trước số lượng.

## Bước 1 — Báo cáo chỉ đọc

Chạy trong SQL Editor của Supabase **trước khi** deploy V67. Toàn bộ là `SELECT`, không sửa gì.

```sql
-- Tồn đọng theo nguồn và theo tuổi
select source,
       count(*) as orders,
       min(created_at) as oldest,
       max(created_at) as newest,
       count(*) filter (where created_at < now() - interval '7 days') as older_than_7d
from app.payment_orders
where status = 'PENDING'
group by source
order by orders desc;
```

```sql
-- Khối lượng lần quét đầu tiên của job: đây là số lời gọi PayOS sẽ phát sinh
select count(*) as real_pending_due
from app.payment_orders
where status = 'PENDING'
  and source = 'REAL'
  and created_at < now() - interval '10 minutes';
```

```sql
-- Danh sách cần người quyết: không có bằng chứng liên kết cổng thanh toán
select order_code, user_id, plan_tier, amount, source, created_at,
       payos_payment_link_id is not null as has_link,
       checkout_url is not null as has_checkout_url
from app.payment_orders
where status = 'PENDING'
  and source <> 'REAL'
order by created_at;
```

```sql
-- Người vừa có đơn PENDING vừa có đơn PAID: KHÔNG được suy ra đơn chờ đã bị huỷ
select p.user_id,
       count(*) filter (where p.status = 'PENDING') as pending,
       count(*) filter (where p.status = 'PAID') as paid
from app.payment_orders p
group by p.user_id
having count(*) filter (where p.status = 'PENDING') > 0
   and count(*) filter (where p.status = 'PAID') > 0;
```

Lưu kết quả bốn câu này lại làm ảnh "trước" — mục 4 tài liệu bàn giao yêu cầu có dấu vết trước/sau.

## Bước 2 — Xử lý theo từng nhóm

**`REAL`.** Không làm tay. Deploy V67, bật `app.payment.reconcile-job-enabled`, để job đối soát với
PayOS rồi cập nhật theo kết quả thật. Nếu con số ở câu thứ hai lớn, hạ `reconcile-batch-size` hoặc
giãn `reconcile-job-interval` cho lần chạy đầu để không ăn 429 của cổng, rồi trả lại mặc định.

**`DEMO`.** Đây là dữ liệu thử nghiệm, không phải tiền thật, và không có gì ở PayOS để đối soát.
Chốt một chính sách rồi ghi lại, ví dụ đánh `CANCELLED` toàn bộ đơn DEMO quá hạn. Dù chọn gì cũng
chỉ UPDATE cột `status` (và `updated_at`): **không** chạm `source` hay `payos_payment_link_id`, vì
trigger `trg_payment_source_legacy_writer` (V45) nổ theo danh sách cột của UPDATE.

**`LEGACY_UNKNOWN`.** Không suy diễn. Không có liên kết PayOS nghĩa là không có bằng chứng để nói
đơn đã trả hay đã huỷ. Báo cáo từng dòng ở câu thứ ba cho chủ dự án quyết, và chỉ đụng vào những
dòng đã được xác nhận bằng tên.

## Cấm

- Không xoá đơn chờ hàng loạt để làm sạch danh sách.
- Không tự huỷ một đơn chỉ vì cùng người dùng có một đơn PAID khác. Trạng thái thuộc về mã đơn,
  không thuộc về người dùng.
- Không đổi `source`, `amount`, `paid_at` hay thời hạn gói cũ.
- Không tạo thêm bản ghi doanh thu hoặc cấp lại quyền lợi trong lúc đối soát.
- Không chạy migration hay fixture test trên DB ứng dụng dùng chung.

## Bước 3 — Ảnh "sau"

Chạy lại câu thứ nhất sau khi job đã quét hết và sau khi xử lý xong DEMO/LEGACY_UNKNOWN. Kèm cả hai
ảnh vào phần bàn giao cho FE.
