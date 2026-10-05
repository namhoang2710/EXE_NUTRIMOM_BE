# Family Invitation API (mời thành viên gia đình)

Mẹ bầu mời người nhà vào nhóm gia đình bằng **email** hoặc **số điện thoại**, kèm danh sách quyền
(`FamilyScope`) mà người đó sẽ được xem.

Tất cả endpoint cần JWT. JSON snake_case, bọc trong `ApiResponse`; lỗi theo
[error-codes.md](error-codes.md).

## Hai điều phải nắm trước

**1. Người được mời phải đã có tài khoản.** `accept` đối chiếu email/số điện thoại của lời mời với
tài khoản đang đăng nhập; lệch là `403 INVITATION_TARGET_MISMATCH`. Không có luồng "đăng ký mới từ
link mời". Email mời vì vậy ghi rõ *"hãy đăng nhập bằng chính email này"*, và `preview` trả
`masked_target` để giao diện nhắc lại trước khi người ta bấm.

**2. Mời bằng số điện thoại thì hệ thống KHÔNG gửi gì ra ngoài.** Dự án chưa tích hợp nhà cung cấp
SMS nào. Trường hợp đó `delivery_status` là `SKIPPED` và chủ nhóm phải tự gửi `invite_url`. Đây là
hợp đồng mà client **bắt buộc** phải xử lý — bỏ qua nó thì mẹ bầu ngồi chờ một tin nhắn không bao
giờ tới.

| Mời bằng | Email HTML | Thông báo in-app | `delivery_status` |
|---|---|---|---|
| `invited_email` | Có | Có, nếu email khớp một tài khoản | `SENT` / `FAILED` |
| `invited_phone` | Không | Có, nếu sđt khớp một tài khoản | `SKIPPED` |

Thông báo in-app đi qua `NotificationService`, nên khi có app mobile thì `PushSender` phía sau tự lo
phần đẩy push — module này không phải sửa gì.

## Link trong email

Luôn là `https://` thường, dạng `{app.frontend.base-url}{app.frontend.invite-path}?token=...`.
**Không bao giờ** dùng scheme riêng kiểu `nutrimom://`: mail client không mở được, và người chưa cài
app sẽ bấm vào một link chết. Ngược lại, một link https khi sau này có app mobile khai báo Universal
Links (iOS) / App Links (Android) thì **chính nó** sẽ tự mở app nếu máy đã cài, mở web nếu chưa —
không phải sửa template lẫn backend.

## Endpoints

| Method | Path | Ghi chú |
|---|---|---|
| POST | `/api/v1/family-invitations` | Chủ nhóm tạo lời mời; gửi đi ngay |
| GET | `/api/v1/family-invitations` | Chủ nhóm xem danh sách; **không** kèm token |
| GET | `/api/v1/family-invitations/preview?token=` | Người được mời xem trước |
| DELETE | `/api/v1/family-invitations/{id}` | Chủ nhóm thu hồi; idempotent |
| POST | `/api/v1/family-invitations/accept` | Người được mời chấp nhận |

### POST `/api/v1/family-invitations`

Body: đúng **một** trong `invited_email` / `invited_phone`, cộng `relationship`, `scopes`
(không rỗng), `expires_in_hours` (1–168, mặc định 48).

```json
{"data":{"id":"…","family_group_id":"…","invited_email":"an@gmail.com",
 "token":"<raw>","invite_url":"http://localhost:5173/family/invite?token=…",
 "relationship":"PARTNER","scopes":["SHARED_CALENDAR"],
 "status":"PENDING","delivery_status":"SENT","sent_at":"…",
 "expires_at":"…","created_at":"…"}}
```

`token` vẫn được trả về như trước. Giấu nó đi không mua được gì khi `invite_url` ngay bên cạnh đã
chứa chính nó, mà lại phá giao diện đang chạy — và khi `delivery_status` khác `SENT` thì copy tay là
đường duy nhất còn lại.

**Client phải phân biệt `delivery_status`:**

| Giá trị | Nghĩa | Giao diện nên làm |
|---|---|---|
| `SENT` | Đã gửi email | "Đã gửi lời mời tới a\*\*\*@gmail.com" |
| `FAILED` | Có email nhưng SMTP hỏng / chưa cấu hình | Báo chưa gửi được + nút copy link |
| `SKIPPED` | Mời bằng số điện thoại | Báo hệ thống chưa gửi SMS được + nút copy link |

Lỗi: `404 FAMILY_GROUP_NOT_FOUND` · `422 VALIDATION_ERROR` (thiếu/thừa target, scope rỗng) ·
`429 RATE_LIMITED` (10 lời mời mỗi giờ).

### GET `/api/v1/family-invitations/preview?token=`

Cần đăng nhập — người được mời dù sao cũng phải có tài khoản mới chấp nhận được, nên mở endpoint này
ra public chỉ tặng thêm một bề mặt để dò token.

```json
{"data":{"inviter_display_name":"Mai","relationship":"PARTNER",
 "relationship_label":"Chồng/bạn đời","scopes":["SHARED_CALENDAR"],
 "scope_labels":["Xem lịch khám và nhắc nhở"],
 "target_type":"EMAIL","masked_target":"a***@gmail.com",
 "expires_at":"…","status":"PENDING"}}
```

`status` ∈ `PENDING | ACCEPTED | REVOKED | EXPIRED`. **EXPIRED suy ra lúc đọc, không lưu trong DB.**

Hết hạn / đã dùng / đã thu hồi đều trả **200 kèm `status`**, không phải lỗi: màn hình cần nói được
"lời mời đã hết hạn, xin link mới" chứ không phải một trang lỗi trống. Chỉ token **không tồn tại**
mới là `404 INVALID_INVITATION_TOKEN` — và token của nhóm khác cũng rơi vào đúng nhánh đó, không có
thông điệp riêng.

Response **không bao giờ** chứa `token`, `token_hash`, `id`, `family_group_id`, `pregnancy_id`,
`owner_user_id`, hay địa chỉ/số đầy đủ.

### GET `/api/v1/family-invitations`

Trả mảng `{id, target_type, masked_target, relationship, scopes, status, delivery_status, sent_at,
expires_at, created_at}`.

Không có `token` lẫn `invite_url`: token thô chỉ sống đúng một lần ở response lúc tạo, DB chỉ giữ
bản băm nên không dựng lại được — và cũng không nên.

### DELETE `/api/v1/family-invitations/{id}`

`204`. Thu hồi lại một lời mời đã thu hồi vẫn `204` (kết quả mong muốn đã đạt). Lời mời của nhóm
khác trả `404 RESOURCE_NOT_FOUND`, không phải 403 — đúng quy ước chống IDOR đang dùng cho member.
Lời mời đã được chấp nhận trả `409 INVITATION_ALREADY_USED`.

### POST `/api/v1/family-invitations/accept`

Body `{"token":"…"}`. Lỗi: `404 INVALID_INVITATION_TOKEN` · `409 INVITATION_ALREADY_USED` ·
`409 INVITATION_REVOKED` · `422 INVITATION_EXPIRED` · `403 INVITATION_TARGET_MISMATCH` ·
`409 OWNER_ALREADY_IN_GROUP` · `409 FAMILY_MEMBER_EXISTS`.

Chấp nhận thành công sẽ gửi cho **chủ nhóm** một thông báo in-app `FAMILY` ("Lời mời đã được chấp
nhận"), để họ không phải tự vào xem danh sách mới biết.

## Cấu hình

| Biến | Mặc định | Mục đích |
|---|---|---|
| `APP_FRONTEND_BASE_URL` | `http://localhost:5173` | **Origin thuần** của web, để dựng link mời |
| `APP_FRONTEND_INVITE_PATH` | `/family/invite` | Đường dẫn trang nhận lời mời |
| `NUTRIMOM_FAMILY_INVITE_SEND_ENABLED` | `true` | Tắt thì chỉ trả `invite_url`, không gửi gì |
| `NUTRIMOM_FAMILY_INVITE_EXPIRY_HOURS` | `48` | Hạn mặc định |

`APP_FRONTEND_BASE_URL` cố ý tách khỏi `MAGIC_LINK_BASE_URL`: biến kia là một **đường dẫn đầy đủ**
(`.../auth/verify`), biến này là **origin**. Gộp hai thứ khác ngữ nghĩa vào một khoá là cách chắc
chắn để một hôm nào đó link mời trỏ vào trang xác thực.

Chưa cấu hình SMTP thì `EmailService` rơi về chế độ mock và **in cả nội dung HTML ra log** — tiện
khi dev, nhưng nghĩa là link chấp nhận nằm trong log, nên production phải luôn có SMTP thật.

## Rate limit

| Route | Ngưỡng |
|---|---|
| `/api/v1/family-invitations` (tạo) | 10 / giờ |
| `/api/v1/family-invitations/preview` | 20 / phút |
| `/api/v1/family-invitations/accept` | 20 / phút |

Hai route sau khoá chặt hơn vì chúng nhận token — đây là lớp chống dò.
