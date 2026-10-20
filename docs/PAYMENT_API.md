# NutriMom — Thanh toán & lịch sử giao dịch

> Module `vn.nutrimom.payment`. Catalog gói và việc chặn tính năng theo gói nằm ở
> `vn.nutrimom.entitlement` — xem [ENTITLEMENTS_API.md](ENTITLEMENTS_API.md).

Cổng thanh toán duy nhất là **PayOS** (VietQR). Không có VNPay/MoMo/Casso.

## Thời hạn gói

| Gói | Giá | Thời hạn server cấp |
|---|---|---|
| `PLAN_99K` | 99.000đ | 30 ngày |
| `PLAN_399K` | 399.000đ | **10 tháng lịch** (303–305 ngày tuỳ ngày mua) |

Nguồn sự thật là `PlanTier.getTerm()`. `PaymentService.activateOrderAndSubscription` và
`PlanCatalog` đọc cùng giá trị đó, nên thứ trang giá công bố và thứ server cấp không lệch được.

**Gia hạn cộng dồn, và chỉ nâng tier.** Thanh toán thành công khi đang còn gói hiệu lực sẽ cộng
thời hạn của gói vừa mua vào `end_date` hiện tại. Tier chỉ đổi khi gói mới **cao hơn** — mua
`PLAN_99K` lúc đang giữ `PLAN_399K` thì được thêm 30 ngày nhưng vẫn giữ quyền lợi 399K.

> Đổi thời hạn **không cần migration**: `app.user_subscriptions` chỉ lưu `start_date`/`end_date`,
> không có cột duration. Thay đổi chỉ áp cho đơn thanh toán mới; subscription đang chạy giữ nguyên
> `end_date` đã lưu.

## `GET /api/v1/payments/orders` — lịch sử giao dịch

Bearer token. Trả giao dịch của **chính người gọi**, mới nhất trước.

Query param viết **camelCase**, body trả về vẫn **snake_case** — cùng quy ước với
`/consultation-requests` và `/admin/payment-orders`; `SNAKE_CASE` của Jackson chỉ áp cho body.

| Tham số | Mặc định | Ghi chú |
|---|---|---|
| `page` | `1` | ≥ 1 |
| `pageSize` | `20` | 1–100 |
| `status` | — | `PENDING` \| `PAID` \| `CANCELLED` \| `EXPIRED` |
| `planTier` | — | `FREE` \| `PLAN_99K` \| `PLAN_399K` |
| `fromDate` | — | `YYYY-MM-DD`, ngày lịch UTC, lọc theo `created_at` |
| `toDate` | — | `YYYY-MM-DD`, **bao trọn cả ngày đó** |

`GET /api/v1/payments/orders?status=PAID&page=1&pageSize=20`

```json
{
  "data": {
    "items": [
      {
        "id": "…",
        "order_code": 1760934512345,
        "plan_tier": "PLAN_399K",
        "plan_name": "Gói Toàn diện 399K",
        "amount": 399000,
        "currency": "VND",
        "status": "PAID",
        "source": "REAL",
        "description": "NutriMom PLAN_399K",
        "paid_at": "2026-10-20T07:31:04Z",
        "created_at": "2026-10-20T07:28:32Z",
        "updated_at": "2026-10-20T07:31:04Z"
      }
    ],
    "page": 1,
    "page_size": 20,
    "total_items": 1,
    "total_pages": 1
  }
}
```

Ba điều người đọc endpoint này cần biết:

1. **Đơn `PENDING` ở đây KHÔNG được đồng bộ lại với PayOS.** Một trang toàn đơn dở dang sẽ thành
   ngần ấy lời gọi mạng trong một request. Muốn trạng thái mới nhất của một đơn thì gọi endpoint
   chi tiết bên dưới — chính nó mới đồng bộ ngược.
2. **Không có job nào dọn đơn `PENDING` bỏ dở.** Mỗi lần người dùng mở checkout rồi bỏ ngang là một
   dòng `PENDING` nằm lại vĩnh viễn. FE muốn danh sách sạch thì lọc `status=PAID`.
3. **Không phải dòng nào cũng là giao dịch người dùng tự thực hiện.** Trigger V46 sinh đơn `PAID`
   với `source = LEGACY_UNKNOWN` và không có `checkout_url` mỗi khi một gói được cấp thẳng trong
   Postgres (xem [payment-revenue-incident-2026-10-13.md](payment-revenue-incident-2026-10-13.md)).
   Dùng `source` để gắn nhãn, và **đừng vẽ nút "thanh toán tiếp" khi `checkout_url` vắng mặt**.

`checkout_url` và `qr_code` chỉ có nghĩa khi đơn còn `PENDING`; với đơn đã xong chúng là link chết.
Cả hai nullable và `default-property-inclusion: non_null` nên key sẽ **vắng hẳn** chứ không trả `null`.

`order_code` giữ **kiểu số**: V47 đã hạ trần sequence xuống `9007199254740991` nên vẫn an toàn với
`Number` của JS, và `docs/admin-operations-api.md` chốt API phía user giữ kiểu số để tương thích.

### Mã lỗi

| Tình huống | HTTP | `error.code` |
|---|---|---|
| `page`/`pageSize` ngoài khoảng | 422 | `VALIDATION_ERROR` |
| `status`/`planTier`/ngày sai định dạng | 400 | `INVALID_REQUEST_PARAMETER` |

Hai mã khác nhau trên cùng một endpoint là do `@Min/@Max` và lỗi bind tham số đi hai đường khác nhau
trong `GlobalExceptionHandler` — nhất quán với phần còn lại của repo.

## `GET /api/v1/payments/orders/{orderCode}` — chi tiết giao dịch

Bearer token. Cùng shape một phần tử `items` ở trên.

Khác endpoint lịch sử ở chỗ **có đồng bộ ngược từ PayOS**: nếu đơn đang `PENDING`, là `source=REAL`
và cổng đã cấu hình, server hỏi lại PayOS và kích hoạt gói ngay nếu đã thanh toán. Đây là cách trang
`payment/success` của FE biết kết quả mà không cần chờ webhook. Vì vậy endpoint này là
`@Transactional` **ghi**, không phải `readOnly`.

**Đơn của người khác và đơn không tồn tại cho ra cùng một `404 PAYMENT_ORDER_NOT_FOUND.`** Trả 403
là tự xác nhận "đơn này có thật, chỉ không phải của bạn" — trái quy ước `docs/security.md` mục 2.
Chủ sở hữu được lọc **ngay trong truy vấn** (`findByOrderCodeAndUserIdForUpdate`), không phải so
sánh sau khi đã lấy khoá: khoá trước rồi mới so owner nghĩa là chỉ cần đoán `order_code` là giữ được
row-lock trên đơn của người khác tới hết transaction. Ghim ở
`PaymentIntegrationTest#oneUserCannotReadAnotherUsersOrder`.

## Các endpoint còn lại

| Method | Path | Ghi chú |
|---|---|---|
| `POST` | `/api/v1/payments/subscription-checkout` | Tạo đơn + link PayOS. Gói `FREE` → `INVALID_PLAN_TIER` |
| `GET` | `/api/v1/payments/my-subscription` | Gói hiện tại (hợp đồng cũ, giữ nguyên cho luồng thanh toán) |
| `POST` | `/api/v1/payments/webhook` | PayOS gọi, **permitAll**, bắt buộc verify chữ ký. Payload không ký không bao giờ kích hoạt gói |
| `POST` | `/api/v1/payments/orders/{orderCode}/confirm-mock` | Chỉ khi `payos.demo-enabled` và đơn có `source=DEMO` |

`GET /api/v1/subscriptions/me` (module entitlement) trả gói hiện tại **kèm quyền lợi đã phân giải**.
Cố ý không nhét `entitlements` vào `/payments/my-subscription`: `entitlement` đã phụ thuộc `payment`,
nhét ngược lại sẽ thành phụ thuộc vòng.
