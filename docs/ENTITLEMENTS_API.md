# NutriMom — Subscription catalog & entitlements (spec mục 17)

> Module `vn.nutrimom.entitlement`. Hạ tầng thanh toán (`vn.nutrimom.payment`: checkout, PayOS
> webhook, mock confirm) là module khác và **không bị đụng** trong task này.

## Vấn đề task này vá

Trang giá của FE hứa khoá tính năng theo gói (`pricing-catalog.ts`), nhưng backend không chặn gì:
user FREE vẫn mời được gia đình và tạo được lịch nhắc. Ngoài ra FE hardcode `99000`/`399000`, trong
khi spec mục 17 ghi rõ *"Không lấy giá từ Flutter"*.

## Endpoint

### `GET /api/v1/subscription-plans` — công khai, không cần token

Trang giá phải render được khi chưa đăng nhập, nếu không FE vẫn buộc phải hardcode giá.

```json
{
  "data": [
    {
      "id": "FREE",
      "name": "Gói Miễn phí",
      "description": "Khởi đầu nhẹ nhàng với những công cụ thiết yếu cho hành trình của mẹ.",
      "price": 0,
      "currency": "VND",
      "duration_days": 0,
      "duration_iso": "P0D",
      "duration_label": "Mãi mãi",
      "featured": false,
      "features": [
        { "code": "FAMILY_SHARING", "label": "Liên kết gia đình", "included": false },
        { "code": "CONCEPTION_CALCULATOR", "label": "Tính ngày thụ thai", "included": false },
        { "code": "MEDICAL_RECORDS", "label": "Lưu hồ sơ sức khỏe", "included": true },
        { "code": "AI_SCAN", "label": "AI Scan Vision", "included": true },
        { "code": "CALENDAR_REMINDERS", "label": "Lịch nhắc nhở", "included": false },
        { "code": "PERSONALISED_GUIDANCE", "label": "Gợi ý cá nhân toàn diện", "included": false },
        { "code": "CHATBOT", "label": "Chatbot đồng hành", "included": true },
        { "code": "CONSULTATION", "label": "Tư vấn", "included": true },
        { "code": "DOCTOR_BOOKING", "label": "Đặt lịch bác sĩ", "included": true },
        { "code": "SUPPORT", "label": "Luôn nhận hỗ trợ", "included": true }
      ],
      "scan_quota": { "limit": 2, "period": "LIFETIME", "unlimited": false }
    }
  ],
  "meta": { "request_id": "...", "server_time": "..." }
}
```

### Thời hạn gói: ba field, đọc field nào cho việc gì

| Field | Dùng khi | FREE | PLAN_399K | PLAN_99K |
|---|---|---|---|---|
| `duration_iso` | **Nguồn chính xác.** `Period.toString()`, `Period.parse()` round-trip được | `P0D` | `P10M` | `P30D` |
| `duration_label` | Hiển thị — FE render thẳng, đừng tự ghép từ con số | `Mãi mãi` | `10 tháng` | `30 ngày` |
| `duration_days` | Số ngày **danh nghĩa** (tháng = 30), giữ cho FE cũ | `0` | `300` | `30` |

PLAN_399K là **10 tháng lịch**: ngày hết hạn rơi đúng ngày đó 10 tháng sau, nên số ngày thực tế là
303–305 tuỳ ngày mua. `duration_days: 300` là xấp xỉ có chủ ý — cần con số đúng thì đọc `duration_iso`.

`duration_days` **luôn có mặt và luôn là `int`**. Đừng đổi nó sang nullable: `default-property-inclusion:
non_null` sẽ **xoá hẳn field khỏi JSON** chứ không trả `null`, tức âm thầm đổi hợp đồng của một
endpoint công khai. Ghim ở `SubscriptionPlanIntegrationTest#everyPlanPublishesItsTermInAFormTheClientCanRender`.

Hai bất biến của response, **đừng phá**:

1. **Thứ tự gói** là `FREE`, `PLAN_399K`, `PLAN_99K` — thứ tự thẻ trên trang giá, gói nổi bật nằm
   giữa. Test FE `tests/public-pricing.test.ts` ghim đúng thứ tự này.
2. **Mọi gói trả đủ cả 10 feature**, kể cả dòng `included: false`. Bảng so sánh của FE dựng theo
   *vị trí*: nó lấy nhãn từ gói đầu tiên rồi index mọi gói còn lại bằng cùng chỉ số, nên lọc bớt
   dòng là dán sai nhãn cho cả cột sau.

`id` trùng đúng chuỗi `PlanTier` mà FE đang dùng (`FREE | PLAN_99K | PLAN_399K`): deep link
`?plan=PLAN_399K` và `isPaidPlanTier` so sánh literal, lệch hoa thường là hỏng.

### `GET /api/v1/subscriptions/me` — cần bearer token

```json
{
  "data": {
    "plan_id": "PLAN_99K",
    "plan_name": "Gói Cơ bản 99K",
    "paid": true,
    "start_date": "2026-10-01T00:00:00Z",
    "end_date": "2026-10-31T00:00:00Z",
    "days_remaining": 19,
    "features": [ /* như trên, đã phân giải theo gói hiện tại */ ],
    "scan_quota": { "limit": 2, "period": "DAILY", "unlimited": false }
  }
}
```

`paid: false` nghĩa là đang ở FREE (không có subscription hiệu lực); khi đó `start_date`/`end_date`
đều null. **Không có subscription** và **subscription hết hạn** cho ra cùng một kết quả: FREE.

> `GET /api/v1/payments/my-subscription` vẫn giữ nguyên hợp đồng cũ cho luồng thanh toán. Cố ý không
> nhét `entitlements` vào đó: module `entitlement` đã phụ thuộc `payment`, nhét ngược lại sẽ thành
> phụ thuộc vòng.

## Chặn theo gói

| Tính năng | FREE | PLAN_99K | PLAN_399K |
|---|---|---|---|
| `FAMILY_SHARING` | ✗ | ✗ | ✓ |
| `CALENDAR_REMINDERS` | ✗ | ✓ | ✓ |

Chốt chặn (đều là **đường ghi**):

| Endpoint | Service | Feature |
|---|---|---|
| `POST /api/v1/family-groups` | `FamilyGroupService.create` | `FAMILY_SHARING` |
| `POST /api/v1/family-invitations` | `FamilyInvitationService.create` | `FAMILY_SHARING` |
| `POST /api/v1/calendar/reminders` | `CalendarReminderService.create` | `CALENDAR_REMINDERS` |
| `POST /api/v1/medical-records/{id}/reminders` | `CalendarReminderService.createFromMedicalRecord` | `CALENDAR_REMINDERS` |

Bốn quy tắc, mỗi cái có lý do cụ thể — **đừng "dọn" mất**:

- **Chỉ chặn ghi.** `GET/PATCH/DELETE` nhắc nhở và mọi đường đọc family vẫn mở. User tạo dữ liệu lúc
  còn gói, hết hạn rồi vẫn phải xem và xoá được; nếu không thì bật cổng đồng nghĩa với nuốt mất dữ
  liệu của họ. Ghim ở `EntitlementEnforcementIntegrationTest#anExpiredAccountKeepsReadingAndDeletingWhatItAlreadyCreated`.
- **`FamilyGroupService.create` là get-or-create.** Cổng nằm *sau* nhánh trả về nhóm sẵn có, không
  phải đầu hàm — nếu không thì user FREE đã có nhóm từ trước sẽ bị 403 ngay cả khi chỉ muốn đọc.
- **`accept` KHÔNG bị chặn.** Gói của *chủ nhóm* trả tiền cho liên kết; người được mời có thể đang ở
  FREE. Chặn theo gói của invitee là sai nghiệp vụ. Ghim ở
  `#aFreeInviteeCanStillAcceptAPaidOwnersInvitation`.
- **`CalendarReminderJob` giữ nguyên.** Nhắc nhở cũ vẫn bắn sau khi gói hết hạn. Lọc theo gói lúc
  bắn nghĩa là một query gói cho mỗi lần fire, đổi lấy việc im lặng làm hỏng thứ user đang dùng.

Lỗi trả về: `PLAN_UPGRADE_REQUIRED` (403) kèm `fields.{feature, required_plan, current_plan}` —
xem `docs/error-codes.md`.

## Catalog nằm trong code, không trong DB

`PlanCatalog` là hằng Java; **task này không thêm migration nào**. Lý do:

1. **Test tắt Flyway** — catalog nằm trong bảng sẽ vô hình với cả suite, endpoint lẫn test chặn gói
   đều phải seed tay.
2. **Một nguồn sự thật với code chặn** — endpoint và `EntitlementGuard` đọc *cùng một* record. Tách
   ra DB là mở đường cho thứ công bố và thứ enforce lệch nhau mà không test nào bắt được.
3. **Supabase dùng chung** — mỗi lần đổi giá mà phải đẻ migration là cái giá vô ích khi chưa có admin
   UI nào sửa giá.

Cố ý không cho `@ConfigurationProperties` ghi đè giá: nó cho phép config prod lệch với entitlements
đang được enforce. Đổi giá = sửa `PlanCatalog` + chạy lại test.

## Việc còn lại / nợ kỹ thuật

- ~~`duration_days` trả 30 cho cả hai gói trả phí~~ — **đã trả nợ.** Thời hạn nay nằm ở
  `PlanTier.getTerm()`, cả catalog lẫn `PaymentService.activateOrderAndSubscription` đọc *cùng một*
  giá trị nên không lệch được. Đặt ở enum (không phải `PlanCatalog`) vì `entitlement` đã phụ thuộc
  `payment`; cho payment đọc ngược catalog sẽ tạo phụ thuộc vòng. Ghim ở
  `PlanCatalogTest#catalogAndPaymentReadTheSameTerm`.
- **Gia hạn chỉ nâng tier, không bao giờ hạ.** Mua gói rẻ hơn khi đang giữ gói cao thì cộng thời hạn
  nhưng giữ nguyên quyền lợi cao (`PaymentService.activateOrderAndSubscription`). Chặn ngay từ
  checkout sạch hơn nhưng cần error code mới — chưa làm. Ghim ở
  `PaymentSafetyIntegrationTest#buyingACheaperPlanWhileHoldingAHigherOneExtendsWithoutDowngrading`.
- **`AdminOperationsService` giới hạn `expiringWithinDays` ≤ 30** → khách PLAN_399K sẽ vô hình với
  màn hình nhắc gia hạn suốt ~9 tháng đầu. Hệ quả vận hành của thời hạn 10 tháng, chưa xử lý.
- **Không có job nào chuyển subscription hết hạn sang `EXPIRED`** — hết hạn chỉ tính lúc đọc. Vô hại
  với entitlements (đã lọc theo cửa sổ ngày) nhưng báo cáo theo `status` sẽ sai.
- **Ba đường phân giải gói vẫn tồn tại**: `EntitlementService` (mới),
  `PaymentService.getUserSubscription`, `AssistantAccountContextService`. Chúng dùng cùng query +
  cùng `isActive()` nên khớp nhau *do xây dựng*; gom về một chỗ là việc dọn dẹp sau.
- **`AI_SCAN` và `CONCEPTION_CALCULATOR` chưa được enforce.** Module scan chưa tồn tại (task 15);
  `CONCEPTION_CALCULATOR` nằm trong module thai kỳ của người khác và chặn nó sẽ chẹn luôn đường
  onboarding. Catalog vẫn công bố đúng bảng giá để FE hiển thị.
- **Dashboard `scan_quota` vẫn `null`** — chưa có chỗ đếm thì mọi con số `used`/`remaining` đều là
  bịa. Block `subscription` thì đã được điền.
