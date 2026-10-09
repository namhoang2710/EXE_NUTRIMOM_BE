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
| `invited_email`, cờ email tắt | Không | Có, nếu email khớp một tài khoản | `SKIPPED` |
| `invited_phone` | Không | Có, nếu sđt khớp một tài khoản | `SKIPPED` |

**Khuyến nghị cho client: chỉ gửi `invited_email`.** `invited_phone` vẫn còn trong API và vẫn chạy
đúng như bảng trên, nhưng hàng `SKIPPED`-do-sđt là thứ không giao diện nào trình bày gọn được —
"đã tạo lời mời nhưng hệ thống không gửi gì cả, bạn tự copy link đi". Client không gửi
`invited_phone` thì tình huống đó không bao giờ xảy ra, và `delivery_status` thu về đúng hai nghĩa
"đã gửi email" / "chưa gửi được".

Phạm vi được chốt bằng tài liệu chứ không bằng validate, vì hai lý do. Mời bằng sđt là đường duy
nhất tới tài khoản đăng ký bằng OTP — loại tài khoản không có email — nên chặn ở server sẽ đẩy
người dùng vào một màn "thêm email vào tài khoản" mà FE chưa có. Và khi có nhà cung cấp SMS thì
hàng đó tự chuyển thành `SENT` mà không ai phải mở lại chỗ validate.

**3. Hai kênh không chia sẻ số phận.** `delivery_status` chỉ nói về kênh *email*. Thông báo in-app
được tạo độc lập: email hỏng, SMTP chưa cấu hình, hay cờ `NUTRIMOM_FAMILY_INVITE_EMAIL_ENABLED` tắt
đều không ngăn nó. Trước đây cờ đó chặn cả hai kênh, nên một môi trường không cấu hình SMTP làm
người được mời mất luôn lời mời trong app — đó là lỗi, đã sửa.

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
| GET | `/api/v1/family-invitations/preview?token=` | Người được mời xem trước, bằng token |
| DELETE | `/api/v1/family-invitations/{id}` | Chủ nhóm thu hồi; idempotent |
| POST | `/api/v1/family-invitations/accept` | Người được mời chấp nhận, bằng token |
| GET | `/api/v1/family-invitations/received` | Hộp thư của người được mời |
| GET | `/api/v1/family-invitations/{id}/preview` | Người được mời xem trước, bằng id |
| POST | `/api/v1/family-invitations/{id}/accept` | Người được mời chấp nhận, bằng id |

**Hai lối vào, cùng một dòng dữ liệu.** Đường *token* phục vụ link trong email; đường *id* phục vụ
thông báo in-app. Chúng khác nhau ở đúng một chỗ — xem [404 hay 403](#404-hay-403) bên dưới.

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
| `SKIPPED` | Mời bằng sđt, hoặc kênh email bị tắt bằng cấu hình | Báo hệ thống chưa gửi được + nút copy link |
| *(vắng mặt)* | Lời mời đã tạo nhưng chưa ghi được kết quả gửi | **Xử như `FAILED`** — xem dưới |

**Hàng thứ tư không phải trạng thái lỗi.** Email được gửi *sau* khi lời mời đã commit, nên trong
đúng khoảng thời gian SMTP đang chạy (tính bằng giây) một `GET /api/v1/family-invitations` song
song của chính chủ nhóm sẽ trả dòng **không có key** `delivery_status` — Jackson cấu hình
`non_null` nên field biến mất hẳn chứ không phải `null`. Nó cũng vắng vĩnh viễn nếu DB hỏng ngay
sau khi thư đã đi.

`switch` ba nhánh sẽ rơi vào hư vô, nên client phải có nhánh mặc định và cho nó cư xử như `FAILED`.
An toàn cả hai chiều: hiện nút copy link khi email thật ra đã tới thì vô hại, còn giấu nút đi khi
email chưa tới thì có hại.

Lỗi: `404 FAMILY_GROUP_NOT_FOUND` · `422 VALIDATION_ERROR` (thiếu/thừa target, scope rỗng) ·
`429 RATE_LIMITED` (10 lời mời mỗi giờ).

### GET `/api/v1/family-invitations/preview?token=`

**Cần đăng nhập, nhưng cố ý KHÔNG kiểm người gọi có phải người được mời.** Bất kỳ tài khoản đã đăng
nhập nào cầm token hợp lệ đều xem trước được. Đăng nhập ở đây là lớp chống dò token — không phải
kiểm sở hữu; giữ token *chính là* phân quyền trên đường này.

Đó là điều kiện để link trong email dùng được thật: người được mời có thể đang đăng nhập bằng tài
khoản khác, hoặc địa chỉ được mời chưa gắn vào tài khoản nào của họ. Chặn ở bước xem trước chỉ cho
họ một trang lỗi trống mà không ngăn được gì — `accept` vẫn kiểm khớp email/sđt, và đó mới là chỗ
quyết định. Xem [Hai đường xem trước](#hai-đường-xem-trước) để đối chiếu với đường theo id.

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

### GET `/api/v1/family-invitations/received`

Lời mời đang chờ **chính tài khoản đang đăng nhập** xử lý, mới nhất trước. Không phân trang.

Chỉ `PENDING` và **chưa hết hạn**. Danh sách này nuôi một cái badge, nên mọi dòng trong đó phải bấm
được: lời mời đã chấp nhận thì xem ở `GET /api/v1/family-members`, đã thu hồi thì người được mời
không làm gì được, đã hết hạn thì màn chi tiết vẫn giải thích được.

Mỗi dòng mang `id`, `inviter_display_name`, `relationship` + nhãn, `scopes` + nhãn, `target_type`,
`masked_target`, `status`, `expires_at`, `created_at`. Cố ý **không** mang `delivery_status` /
`sent_at`: đó là chuyện vận hành của chủ nhóm, và `sent_at` là một kênh phụ hé lộ hạ tầng của họ.

Tra theo chính email/sđt được mời, so với giá trị **sống** trên tài khoản — không có cột
`invited_user_id`. Người được mời có thể đăng ký *sau* khi lời mời được tạo, hoặc đổi email sau đó;
một cột đóng băng lúc tạo sẽ lệch với điều kiện mà `accept` kiểm và cho ra lời mời hiện trong hộp
thư nhưng bấm vào thì bị từ chối.

### GET `/api/v1/family-invitations/{id}/preview`

Giống `preview?token=` về nội dung trả về. Khác ở chỗ **không lọc trạng thái**: hết hạn / đã thu hồi
/ đã dùng đều `200` kèm `status`.

Hai endpoint cố ý **bất đối xứng**: `/received` chỉ chứa thứ hành động được, `/{id}/preview` thì
luôn giải thích được — kể cả khi người ta mở một thông báo cũ đã nằm trong máy nhiều ngày.

### POST `/api/v1/family-invitations/{id}/accept`

Không có body. Lỗi giống `accept` bằng token, trừ một điểm: lời mời không gửi cho mình trả
`404 RESOURCE_NOT_FOUND` chứ không phải `403 INVITATION_TARGET_MISMATCH`.

### Hai đường xem trước

Hai endpoint xem trước cùng trả một DTO nhưng phân quyền khác hẳn nhau. Bất đối xứng là cố ý.

| | `?token=` | `{id}/preview` |
|---|---|---|
| Đến từ | Link trong email | Thông báo in-app |
| Bắt đăng nhập | Có | Có |
| Kiểm người gọi là người được mời | **Không** | **Có** |
| Người lạ cầm được định danh | `200` | `404` |
| Không tồn tại | `404 INVALID_INVITATION_TOKEN` | `404 RESOURCE_NOT_FOUND` |

Khác nhau vì thứ người gọi cầm khác nhau. Token là bí mật dùng một lần, chỉ người mở được hộp thư
kia mới có — giữ được nó đã là chứng minh đủ, và kiểm thêm email chỉ chặn đúng những người hợp lệ
đang đăng nhập nhầm tài khoản. Id thì không bí mật: nó nằm trong deep link thông báo và trong danh
sách của chủ nhóm, nên ở đó phải kiểm địa chỉ, và trả 404 chứ không 403.

Đăng nhập trên đường token vì vậy **không** phải kiểm sở hữu — nó là lớp chống dò token, để
`/preview?token=` không thành một oracle quét được từ ngoài.

Cả hai đường đều dừng ở cùng một chỗ: `accept` và `{id}/accept` đều đối chiếu email/sđt sống của
tài khoản đang đăng nhập. Xem trước thì mở, tham gia thì không.

### 404 hay 403

Quy tắc không phải "không khớp thì 403", mà là: **response không được tiết lộ nhiều hơn những gì
người gọi đã chứng minh là họ biết.**

| Lối vào | Người gọi đã chứng minh | Không phải của họ |
|---|---|---|
| `?token=` / `accept` | giữ được bí mật | `403 INVITATION_TARGET_MISMATCH` kèm `masked_target` |
| `{id}/preview` / `{id}/accept` | không gì cả | `404 RESOURCE_NOT_FOUND` |

Giữ token thì người gọi đã đọc được cả preview rồi; giấu sự tồn tại không mua được gì, mà thứ họ
cần là thông điệp hành động được — "bạn đang đăng nhập nhầm tài khoản". Id thì ngược lại: nó nằm
trong deep link thông báo và trong danh sách của chủ nhóm, nên 403 ở đó sẽ thành chỗ dò "id này có
tồn tại không".

### Deep link của thông báo

`nutrimom://family/invitations/{invitation_id}` — **không mang token**.

Bản trước ghi `?token=<raw>` vào `notifications.deep_link`, tức là cất một token dùng được trong DB
dưới dạng chữ thường, vô hiệu hoá chính lý do tồn tại của cột `token_hash`; token còn nằm lại đó cả
sau khi lời mời đã được chấp nhận hay thu hồi, và được `GET /api/v1/notifications` trả nguyên văn.
Ba endpoint theo id ở trên tồn tại để deep link không cần mang bí mật nữa.

`V39` dọn nốt những dòng tạo trước đó: deep link nào còn `token=` thì được dựng lại theo
`source_id`, dòng nào không dựng lại được thì bỏ hẳn deep link. Không notification nào bị xoá —
người được mời vẫn thấy là họ từng được mời, và mở lại được qua hộp thư lời mời.

## Cấu hình

| Biến | Mặc định | Mục đích |
|---|---|---|
| `APP_FRONTEND_BASE_URL` | `http://localhost:5173` | **Origin thuần** của web, để dựng link mời |
| `APP_FRONTEND_INVITE_PATH` | `/family/invite` | Đường dẫn trang nhận lời mời |
| `NUTRIMOM_FAMILY_INVITE_EMAIL_ENABLED` | `true` | Tắt thì không gửi email, chỉ trả `invite_url`. **Không** tắt thông báo in-app |
| `SPRING_MAIL_HOST` · `_PORT` · `_USERNAME` · `_PASSWORD` | — | Thiếu `HOST` thì không có bean `JavaMailSender` → `delivery_status=FAILED`, `error=NOT_CONFIGURED` |
| `APP_MAIL_FROM` | *(trống)* | Địa chỉ gửi. **Để trống thì rơi về `SPRING_MAIL_USERNAME`** |
| `SPRING_MAIL_SSL_TRUST` | `smtp.gmail.com` | Host được tin cert. Đổi khi dùng provider khác |
| `SPRING_MAIL_CONNECT_TIMEOUT` | `5000` | ms, timeout mở kết nối SMTP |
| `SPRING_MAIL_READ_TIMEOUT` | `10000` | ms, timeout đọc |
| `SPRING_MAIL_WRITE_TIMEOUT` | `10000` | ms, timeout ghi |
| `NUTRIMOM_FAMILY_INVITE_EXPIRY_HOURS` | `48` | Hạn mặc định |

`APP_FRONTEND_BASE_URL` cố ý tách khỏi `MAGIC_LINK_BASE_URL`: biến kia là một **đường dẫn đầy đủ**
(`.../auth/verify`), biến này là **origin**. Gộp hai thứ khác ngữ nghĩa vào một khoá là cách chắc
chắn để một hôm nào đó link mời trỏ vào trang xác thực.

### Địa chỉ gửi

`APP_MAIL_FROM` mặc định **trống là cố ý**. Trước đây nó mặc định `no-reply@nutrimom.vn`, nên
`EmailService` không bao giờ rơi về `SPRING_MAIL_USERNAME` và mọi thư đều gửi từ một địa chỉ mà tài
khoản SMTP không sở hữu — Gmail từ chối hoặc lặng lẽ viết lại, thư không tới nơi trong khi phía
mình trông như đã gửi xong. Thiếu cả hai biến thì backend **không thử gửi**, trả `FAILED` kèm
`error=NO_SENDER`.

Địa chỉ gửi phải được chính nhà cung cấp SMTP cho phép. Với Gmail nghĩa là trùng
`SPRING_MAIL_USERNAME`, hoặc một alias đã xác thực trong tài khoản đó.

### Timeout

Không có timeout thì một SMTP không phản hồi sẽ treo request vô hạn — và vì lời mời gửi mail
**trong transaction**, nó giữ luôn một connection của pool và khoá dòng lời mời. Ba biến trên đặt
trần hữu hạn; chỉnh theo độ trễ thật của provider ở staging.

### Chế độ mock

Chưa cấu hình SMTP thì `EmailService` rơi về chế độ mock: nó **chỉ log địa chỉ người nhận đã che**
(`a***@example.com`) rồi trả `FAILED`. Nội dung thư, invite URL và token **không bao giờ** được
ghi ra log — thân thư mang bí mật dùng được ngay, nên in ra log là biến mọi người đọc được log
thành người chiếm được tài khoản.

## Rate limit

| Route | Ngưỡng |
|---|---|
| `/api/v1/family-invitations` (tạo) | 10 / giờ |
| `/api/v1/family-invitations/preview` | 20 / phút |
| `/api/v1/family-invitations/accept` | 20 / phút |
| `/api/v1/family-invitations/received` | 20 / phút |
| `/api/v1/family-invitations/{id}/preview` | 20 / phút |
| `/api/v1/family-invitations/{id}/accept` | 20 / phút |

Năm route sau khoá chặt hơn vì chúng nhận token hoặc id — đây là lớp chống dò. Chúng dùng chung
policy `invitation-token`; tên giữ nguyên dù route theo id không mang token, vì ngưỡng mới là thứ
đáng quan tâm.

`RateLimitFilter` lấy rule **khớp đầu tiên** và cho qua không giới hạn nếu không rule nào khớp, nên
mọi route mới phải được thêm vào `app.security.rate-limit.routes` — pattern `*` chỉ khớp đúng một
segment, đừng đặt một `/api/v1/family-invitations/*` lên trên `/received`.
