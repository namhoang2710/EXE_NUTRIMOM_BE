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
và cổng đã cấu hình, server hỏi lại PayOS rồi áp kết quả. Đây là cách trang `payment/success` của FE
biết kết quả mà không cần chờ webhook. Từ V67 đường đồng bộ nhận đủ cả `PAID`, `CANCELLED` lẫn
`EXPIRED` chứ không riêng `PAID`.

Endpoint **không** còn mở transaction bao quanh lời gọi PayOS và **không** còn khoá dòng. Bản cũ giữ
cả hai suốt lời gọi HTTP, nên mỗi lần FE poll là một dòng bị khoá tới khi cổng trả lời. Giới hạn
nhịp hỏi cổng nằm ở cột `reconcile_attempt_at` (`app.payment.reconcile-cooldown`, mặc định 5 phút):
FE poll hai giây một lần cũng chỉ sinh một lời gọi PayOS mỗi chu kỳ.

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
| `POST` | `/api/v1/payments/orders/{orderCode}/cancel` | Chủ đơn huỷ đơn đang chờ. Idempotent, xem mục dưới |
| `POST` | `/api/v1/payments/orders/{orderCode}/confirm-mock` | Chỉ khi `payos.demo-enabled` và đơn có `source=DEMO` |

`GET /api/v1/subscriptions/me` (module entitlement) trả gói hiện tại **kèm quyền lợi đã phân giải**.
Cố ý không nhét `entitlements` vào `/payments/my-subscription`: `entitlement` đã phụ thuộc `payment`,
nhét ngược lại sẽ thành phụ thuộc vòng.

## Vòng đời đơn thanh toán (V67)

Trước V67 một đơn chỉ có hai lối ra: webhook báo thành công, hoặc nằm `PENDING` vĩnh viễn. Link gửi
sang PayOS không kèm hạn nên nó còn thanh toán được mãi; `EXPIRED` có trong
`ck_payment_orders_status` từ V31 nhưng không có gì ghi nó.

### Hạn checkout

`app.payment.checkout-ttl` (mặc định `PT5M`) quyết định link sống bao lâu. Giá trị được gửi sang
PayOS ở trường `expiredAt` và lưu lại ở `payment_orders.expires_at`.

> `expiredAt` của PayOS là **Unix timestamp tính bằng GIÂY**, kiểu `Int32` theo tài liệu cổng; ví dụ
> chính thức trong SDK (`PaymentLinkWithInvoiceExample`) dùng `System.currentTimeMillis() / 1000`.
> Gửi nhầm mili-giây thì giá trị vượt `Int32` và link coi như không có hạn — hỏng im lặng, không có
> lỗi nào nổ ra. `PaymentOrderLifecycleTest#checkoutSendsTheExpiryAsEpochSecondsAndStoresIt` ghim
> đơn vị này.
>
> Tài liệu PayOS **không nói** mức hạn tối thiểu. Mốc 5 phút cần một lần xác nhận trên môi trường
> thật (`PayOSLiveTest`) trước khi coi là an toàn; nếu cổng đặt sàn cao hơn thì chỉ cần nâng
> `checkout-ttl`, không phải sửa code.

`expires_at` có trong cả `PaymentResponse` (body của checkout) lẫn `PaymentOrderResponse`, dưới key
`expires_at`. **Null nghĩa là không biết, không phải là vô hạn** — chỉ những đơn tạo trước V67 mới
rơi vào ca này, và `default-property-inclusion: non_null` sẽ bỏ hẳn key khỏi JSON.

Đơn `DEMO` cũng được lưu hạn để FE dùng chung một màn hình đếm ngược, nhưng job đối soát bỏ qua
chúng và `confirm-mock` vẫn chạy được sau hạn.

### `POST /api/v1/payments/orders/{orderCode}/cancel`

Bearer token, trả `PaymentOrderResponse`.

| Trạng thái đơn | Kết quả |
|---|---|
| `PENDING` | Gọi `cancel` lên PayOS trước, ghi `CANCELLED` sau. Cổng từ chối thì **không ghi gì** và trả `400 PAYMENT_FAILED` |
| `CANCELLED`, `EXPIRED` | `200`, trả trạng thái hiện tại |
| `PAID` | `409 PAYMENT_ORDER_NOT_CANCELLABLE` |
| Đơn của người khác / không tồn tại | `404 PAYMENT_ORDER_NOT_FOUND` |

Hai trạng thái cuối trả `200` chứ không `409` vì FE luôn đua với job đối soát: người dùng bấm huỷ
một đơn vừa tự hết hạn thì kết quả họ muốn đã có sẵn. `409` dành riêng cho đơn đã `PAID`, nơi xung
đột là thật vì tiền đã vào.

### Checkout là idempotent theo (người dùng, gói)

Gọi checkout lần hai cho **cùng một gói** khi đơn cũ còn hạn sẽ trả lại đúng `order_code`,
`checkout_url` và `qr_code` cũ, không gọi PayOS. Checkout cho **gói khác** sẽ huỷ đơn chờ cũ ở cổng
rồi mới mở đơn mới. Hai link sống cho một ý định mua là đường ngắn nhất tới cảnh tiền về mà không
biết gắn vào đâu.

### Job đối soát

`PaymentReconciliationJob` quét mỗi `app.payment.reconcile-job-interval` (mặc định 5 phút), lấy đơn
`PENDING` + `source=REAL` đã quá `expires_at` (hoặc `created_at + checkout-ttl` với đơn trước V67)
cộng thêm `reconcile-grace`, rồi hỏi PayOS từng đơn. Bảng ánh xạ nằm ở `PayOSStatusMapper`:

| PayOS | Nội bộ |
|---|---|
| `PAID` | `PAID`, **chỉ khi** khớp số tiền và `amountRemaining = 0` |
| `CANCELLED` | `CANCELLED` |
| `EXPIRED` | `EXPIRED` |
| `PENDING` | không đổi, trừ khi đã quá hạn: khi đó gọi `cancel` lên cổng rồi mới ghi `EXPIRED` |
| `PROCESSING` | không đổi — tiền đang trên đường |
| `UNDERPAID` | không đổi — tiền đã vào, chỉ thiếu |
| `FAILED` | không đổi — một lần chuyển khoản hỏng, link vẫn trả được |

Hạn của backend là hạn có hiệu lực, `expiredAt` bên PayOS chỉ là lớp chặn thứ hai. Vì vậy đơn quá
hạn mà cổng vẫn báo `PENDING` phải được **huỷ ở cổng trước**, rồi mới đánh `EXPIRED`: đánh dấu suông
ở DB sẽ để lại một link còn thanh toán được trong khi phía ta đã coi đơn là chết. Đây cũng là cách
duy nhất để những đơn tạo trước V67 ngừng nhận tiền.

### Webhook chỉ là tín hiệu

Webhook mang mã thành công trên một đơn còn `PENDING` thì kích hoạt ngay (payload đã qua kiểm chữ ký
và khớp số tiền). **Mọi mã khác chỉ được ghi log, không ghi trạng thái.** Bản cũ biến mã khác `"00"`
thành `CANCELLED`, nên một lần chuyển khoản trượt là giết luôn một đơn đang lành.

Webhook đến trên đơn đã `CANCELLED`/`EXPIRED` thì server đọc lại cổng; nếu cổng xác nhận đã trả,
đơn được kích hoạt. Với hạn 5 phút, tiền về sau khi đóng sổ là chuyện xảy ra đều đặn chứ không phải
ngoại lệ hiếm, và bên kia đã trả tiền thật. Luật chuyển trạng thái **không đối xứng**: `PAID` đè
được lên `CANCELLED`/`EXPIRED`, chiều ngược lại thì không. Bản thân webhook không hồi sinh được đơn
nào — thứ quyết định là câu trả lời của `paymentRequests().get(orderCode)`.

### Vận hành: đơn `UNDERPAID`

Không có đường tự động nào xử lý ca này, và đó là chủ ý. Khi PayOS báo `UNDERPAID`, tiền đã vào tài
khoản nhưng chưa đủ: đơn giữ nguyên `PENDING`, link **không** bị huỷ (huỷ là cắt mất đường đối chiếu
duy nhất giữa khoản tiền đó và đơn hàng), và log ghi `orderCode`. Người vận hành phải:

1. Tra `orderCode` trên dashboard PayOS để lấy số tiền đã nhận và tài khoản gửi.
2. Liên hệ khách: chuyển nốt phần thiếu, hoặc hoàn lại phần đã nhận.
3. Nếu hoàn tiền: huỷ đơn bằng dashboard PayOS, đơn sẽ về `CANCELLED` ở lượt đối soát kế tiếp.

### Cấu hình

| Khoá | Mặc định | Biến môi trường |
|---|---|---|
| `app.payment.checkout-ttl` | `PT5M` | `NUTRIMOM_PAYMENT_CHECKOUT_TTL` |
| `app.payment.reconcile-job-enabled` | `true` | `NUTRIMOM_PAYMENT_RECONCILE_ENABLED` |
| `app.payment.reconcile-job-interval` | `PT5M` | `NUTRIMOM_PAYMENT_RECONCILE_INTERVAL` |
| `app.payment.reconcile-batch-size` | `50` | `NUTRIMOM_PAYMENT_RECONCILE_BATCH` |
| `app.payment.reconcile-grace` | `PT2M` | `NUTRIMOM_PAYMENT_RECONCILE_GRACE` |
| `app.payment.reconcile-cooldown` | `PT5M` | `NUTRIMOM_PAYMENT_RECONCILE_COOLDOWN` |

Tắt job nghĩa là đơn chờ nằm `PENDING` vĩnh viễn và link cũ còn thanh toán được — chỉ tắt ở môi
trường test.
