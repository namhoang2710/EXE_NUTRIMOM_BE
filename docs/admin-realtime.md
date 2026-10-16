# Admin Realtime — hợp đồng BE cho FE

Nhánh: `feat/admin-operations-dashboard`. Bổ sung SSE cho Dashboard và thông báo, không thay API số liệu đang có. Migration V50 đi cùng code này; không sửa lịch sử V42–V49.

## Bật tính năng

`NUTRIMOM_ADMIN_REALTIME_ENABLED=false` là mặc định. Khi đã migrate V50 và kiểm tra staging/proxy, đặt true rồi khởi động lại BE. Cờ FE `VITE_ADMIN_REALTIME_ENABLED` độc lập và cũng phải được bật sau khi xác nhận tích hợp.

- Flag BE tắt: endpoint trả 404 JSON, không tạo clock hoặc sender SSE.
- Flag bật nhưng không có kết nối: worker không truy vấn snapshot.
- Worker outbox thông báo chạy mỗi 1 giây; một clock SSE riêng kiểm tra DB mỗi 1 giây, không bị một batch outbox chặn heartbeat.
- Giới hạn mỗi BE instance: 100 kết nối tổng, 5 kết nối cho mỗi Admin; cấu hình qua `app.admin.realtime.max-connections` và `max-connections-per-user`. Quota mở/kết nối lại là 20/phút theo user, tách quota API đọc/xuất CSV. Quota RAM/capacity là theo instance.

## API và wire format

Swagger tag: **Admin Realtime**.

`GET /api/v1/admin/realtime/stream`

Headers: `Authorization: Bearer <accessToken>`, `Accept: text/event-stream`. Không nhận query parameter hoặc token qua URL. Không có envelope `{data,meta}` trên SSE.

```text
event: ready
data: {"protocol_version":1,"admin_user_id":"<Admin UUID>","dashboard_revision":"17","notifications_revision":"8","unread_count":3}

event: update
data: {"protocol_version":1,"admin_user_id":"<Admin UUID>","dashboard_revision":"18","notifications_revision":"9","unread_count":4}

event: heartbeat
data: {"protocol_version":1}

event: access_revoked
data: {"protocol_version":1,"reason":"ADMIN_ACCESS_REVOKED"}
```

- Ready là snapshot ban đầu; update là snapshot mới nhất khi phiên bản hoặc count thay đổi. Không replay lịch sử và không yêu cầu Last-Event-ID.
- Hai revision là chuỗi số nguyên không âm. FE giữ chuỗi/BigInt, không ép Number hoặc so sánh từ điển.
- Count chỉ thuộc hộp thư ADMIN của chính principal, không theo category/unreadOnly trên UI.
- Heartbeat mỗi 15 giây khi không có update. Kết nối đóng ở thời điểm token hết hạn hoặc sau tối đa 5 phút; FE có thể reconnect bằng token hợp lệ.
- Mỗi lượt poll dùng một SELECT chung cho các Admin đang kết nối: cùng snapshot DB cho phiên bản, quyền hiện tại và count; không một query/connection.
- Quyền/status được kiểm lại trong lượt poll. Khi bị khóa/thu hồi role: access_revoked rồi đóng. FE dùng luồng từ chối Admin hiện tại, không retry với audience khác.
- BE trả `Cache-Control: no-store, no-transform`, `X-Accel-Buffering: no`. Proxy/CDN phải truyền streaming, không gom buffer/cache hoặc ép response thành JSON. Timeout upstream phải cho phép heartbeat 15 giây và stream 5 phút.

Lỗi bắt tay vẫn là JSON envelope chuẩn của dự án, kể cả Accept chỉ là text/event-stream:

| Status | Ý nghĩa |
| --- | --- |
| 401 | Không có JWT hợp lệ/token đã hết hạn |
| 403 | Thiếu role hoặc tài khoản không còn ACTIVE ADMIN |
| 404 | Feature flag BE chưa bật |
| 422 | Gửi query parameter cho endpoint không nhận tham số |
| 429 | Hết quota mở stream hoặc đạt cap kết nối; có Retry-After |

Async completion chỉ được phép trên đúng stream đã qua xác thực, có marker nội bộ từ controller. Không mở quyền ASYNC cho các API khác.

## Phiên bản trên API GET

API vẫn giữ toàn bộ dữ liệu cũ và bổ sung `data.realtime_revision`:

| API | Phiên bản |
| --- | --- |
| GET `/api/v1/admin/dashboard?...` | Dashboard |
| GET `/api/v1/notifications?audience=ADMIN&...` | Hộp thư Admin của principal |
| GET `/api/v1/notifications/unread-count?audience=ADMIN` | Hộp thư Admin của principal |

Dashboard/list/count lấy dữ liệu và revision trong transaction REPEATABLE_READ. Count có thể lọc category theo API cũ, nhưng SSE luôn gửi tổng toàn hộp thư ADMIN. Response USER hoặc không chỉ định audience không được thêm metadata Admin. Generic CursorPage giữ constructor cũ và bỏ trường revision khi null để tương thích module khác.

## Thay đổi nào phát tín hiệu

V50 theo dõi users/roles, expert profile, lịch và slot, hỗ trợ, payment orders, subscriptions, customer metric observations và thông báo. Sửa qua API hoặc SQL đều được thấy sau commit.

V51 bổ sung consultation_reviews và consultation_video_sessions để cập nhật KPI chuyên gia. API/chart dùng cùng dashboard_revision và stream này; hợp đồng tại `docs/admin-expert-performance.md`.

Collector dùng trạng thái transaction-local trong PostgreSQL. Các revision được tăng tại commit theo thứ tự key cố định, sau khi business writes đã lấy lock; không giữ một global revision lock từ đầu transaction. Nhiều thay đổi cùng scope trong một transaction chỉ tăng một lượt khi chạy với chế độ constraint mặc định DEFERRED. Rollback không làm tăng revision.

Revision là trạng thái mới nhất có tính bền vững, không phải bảng lịch sử sự kiện vô hạn. Các BE instance đều đọc cùng bảng nên outbox xử lý ở instance nào cũng không làm instance khác mất tín hiệu. Không thêm Redis/broker hoặc một connection LISTEN riêng cho từng tab.

SSE báo thay đổi dữ liệu DB. Thời gian tự trôi qua (ngưỡng chờ quá giờ, lịch sang ngày mới, gói hết hạn khi không có transaction) không tự tạo sự kiện nghiệp vụ mới; các luồng làm mới theo thời gian/ngày Việt Nam hiện có vẫn cần được giữ ở FE.

## Sửa gói trực tiếp PostgreSQL

- Thay đổi thực sự về gói/trạng thái/hiệu lực của gói trả phí thuộc tài khoản khách hàng tạo `ADMIN_SUBSCRIPTION_CHANGED`, type SYSTEM, category PAYMENT.
- Tiêu đề: “Gói dịch vụ đã thay đổi”; nội dung chung, không gắn nhãn cấp thủ công. Deep link `nutrimom://admin/users/{userId}`; dùng adapter Users hiện có của FE.
- Source type SUBSCRIPTION_CHANGE; source ID là UUID sự kiện. Một event_key theo subscription + transaction; nhiều chỉnh sửa cùng transaction dùng trạng thái đầu/cuối. Sửa rồi hoàn nguyên trước commit không tạo thông báo. Không tạo cho FREE-only hoặc tài khoản ADMIN/EXPERT.
- Trigger trì hoãn đến commit để thấy payment/outbox đã được ORM flush. Nếu transaction đã có thông báo mua/gia hạn API tương ứng của khách hàng, không tạo thêm thông báo SQL.
- Worker fan-out tới các ACTIVE ADMIN và giữ trạng thái đọc độc lập, dùng cơ chế dedup hiện tại. Thông báo chỉ được tạo từ transaction đã commit; chưa xử lý outbox thì count chưa tăng.
- Không ghi thêm doanh thu từ trigger thông báo. V46–V47 tiếp tục xử lý doanh thu: lưu lại cùng gói hoặc sửa thời hạn không tạo một lần mua mới. Không rút ngắn các gói 180 ngày.
- Migration không backfill thông báo lịch sử. Trường hợp không có Admin hoạt động khi dispatcher xử lý vẫn theo chính sách hiện tại của outbox, không tạo inbox cho tài khoản Admin được tạo sau đó.

## Tích hợp FE

Task FE đã có tại `D:/KI8/FE/EXE_NUTRIMOM_FE/docs/admin-realtime-fe-codex-task.md`; wire format protocol 1 ở trên khớp adapter dự kiến.

FE mở một stream/Admin tab visible, gom thay đổi theo cửa sổ 2 giây, chỉ tải lại Dashboard/list đang hiển thị, count lấy từ snapshot SSE. Không tạo request từng KPI. Không nhận event/GET cũ sau đổi phiên. Khi mất kết nối, áp dụng backoff/cooldown và fallback đã nêu trong task FE. 404 không được dò endpoint liên tục.

Đợt BE này không sửa FE hoặc tự bật cờ FE. Sau khi người làm FE triển khai, vẫn phải smoke test qua proxy và browser thật trước khi bật tính năng trên môi trường dùng chung.

## Theo dõi và giới hạn kiểm chứng

Gauge Micrometer: `nutrimom.admin.realtime.connections`, không tag user ID/token. Lỗi poll chỉ log loại lỗi, đóng stream để FE chuyển fallback; không log JWT hoặc dữ liệu y tế. Sender giới hạn 4 thread và queue theo cap kết nối, tối đa một tác vụ đang chờ cho một client, tránh backlog thông điệp vô hạn.

Xem `docs/admin-realtime-validation.md` để phân biệt kết quả unit/Servlet/PostgreSQL QA và kiểm thử HTTP socket/staging còn cần chạy.
