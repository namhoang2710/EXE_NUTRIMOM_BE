# Thông báo Admin — BE

Nhánh: `feat/admin-operations-dashboard`. Phạm vi: hộp thông báo in-app; không thay đổi UI hoặc tự triển khai lên DB thật.

## Sự kiện đã nối vào nghiệp vụ

| Nhóm | Sự kiện | Điểm phát sinh |
|---|---|---|
| ACCOUNT | Tài khoản mới | Đăng ký bằng mật khẩu, OTP hoặc magic link thành công |
| ACCOUNT | Kích hoạt tài khoản | Xác thực email hoặc magic link chuyển PENDING_ACTIVATION → ACTIVE |
| ACCOUNT | Vô hiệu hóa tài khoản | Người dùng xác thực lại và tự vô hiệu hóa thành công |
| PAYMENT | Mua/gia hạn gói thành công | Xác nhận thanh toán và cập nhật subscription trong cùng transaction |
| APPOINTMENT | Đặt trực tiếp/chờ chuyên gia | Tạo yêu cầu DIRECT/RANDOM thành công |
| APPOINTMENT | Tiếp nhận/hoàn tất/hủy | Chuyển trạng thái lịch qua API nghiệp vụ |
| SUPPORT | Mới/hoàn tất/hủy | Tạo hỗ trợ, Admin hoàn tất, hoặc người gửi hủy |

Không phát sự kiện khi đăng nhập/xem trang, xác thực thất bại, webhook lặp, thao tác bị từ chối hoặc transaction rollback. Không có trigger thông báo khi dev sửa trực tiếp pg. Logic doanh thu nhận diện nâng gói bằng pg của V46/V47 được giữ nguyên, nhưng không đồng nghĩa có sự kiện thông báo.

Admin khóa/mở khóa người dùng, hoàn tiền và cộng đồng chưa có API nghiệp vụ trong dự án, nên chưa có producer tương ứng. Khi triển khai nghiệp vụ đó cần thêm event trong transaction thành công. Không dựng endpoint giả cho tính năng chưa tồn tại.

## Ghi nhận, phân phối và quyền truy cập

- `AdminNotificationEvents` bắt buộc có transaction của nghiệp vụ; ghi một outbox theo `(event, source_id)`. Không gửi email/push hoặc fan-out tại bước này.
- Job chạy mỗi 5 giây, tối đa 50 event/lượt. Mỗi event có transaction riêng và `FOR UPDATE SKIP LOCKED`, hỗ trợ nhiều instance BE chạy đồng thời.
- Worker lấy các ADMIN đang ACTIVE, batch insert hộp thư và đánh dấu event hoàn tất trong cùng transaction. Một event không có Admin hiện tại được hoàn tất; không phát lịch sử cho Admin tạo sau đó.
- Unique `event_key` ở outbox và `dedup_key` theo event + người nhận + source ở notifications chống lặp. Phát lại không làm mất `read_at` đã có.
- Event lỗi rollback toàn bộ fan-out, ghi loại lỗi an toàn và lùi lịch retry tăng dần tới tối đa 15 phút. Event sau tiếp tục xử lý; lỗi DB toàn job có backoff tới 60 giây. Không bỏ event chưa xử lý.
- Hai producer CONTACT_NEW và EXPERT_WAITING cũ giữ cơ chế phát ngay để tương thích. Outbox dùng cùng dedup key, không tạo thêm bản sao.
- Mỗi Admin có bản ghi và trạng thái đọc độc lập. Owner được xác định từ JWT, không nhận `userId` tùy ý từ client. Truy cập thông báo của người khác trả 404.
- Với audience ADMIN, BE kiểm tra role ADMIN và trạng thái ACTIVE hiện tại trong DB. JWT cũ sau khi bị thu hồi quyền không đọc/đếm/đánh dấu thông báo Admin được. API không có filter cũng loại thông báo Admin nếu người gọi không còn quyền; thông báo cá nhân vẫn dùng bình thường.
- Body không chứa email/điện thoại, nội dung liên hệ, ghi chú y tế, OTP, QR hoặc thông tin ngân hàng.
- Đây là thông báo **in-app**. `LoggingPushSender` hiện chỉ là stub của hệ thống push có sẵn; tính năng browser/mobile push thực chưa được triển khai. Event mới không gọi gateway push.

Outbox đã xử lý được giữ để truy vết và chống lặp; không có job xóa dữ liệu. Theo dõi dung lượng bảng khi hệ thống tăng trưởng và thiết kế chính sách lưu trữ riêng trước khi bổ sung cleanup.

## API tương thích và bộ lọc mới

Giữ nguyên API prefix `/api/v1` và snake_case của response:

| Endpoint | Tham số |
|---|---|
| GET `/notifications` | `cursor`, `limit=20` (1–50), `unreadOnly=false`, `audience`, `category` |
| GET `/notifications/unread-count` | `audience`, `category`; trả `{count}` |
| POST `/notifications/{id}/read` | Owner từ JWT; gọi lại giữ nguyên thời điểm đã đọc |
| POST `/notifications/read-all` | `audience`, `category`; chỉ đánh dấu các dòng phù hợp; trả `{updated}` |

`audience`: `USER`, `ADMIN`. `category`: `ACCOUNT`, `PAYMENT`, `APPOINTMENT`, `SUPPORT`, `GENERAL`. Filter không hợp lệ, kể cả chuỗi rỗng, trả 422. Yêu cầu ADMIN khi không đủ quyền trả 403. Cursor sai/limit ngoài 1–50 trả 422; lỗi binding limit không phải số giữ quy ước 400 của framework.

Không truyền filter vẫn hoạt động như API cũ: Admin thấy hộp thư của mình, người dùng/chuyên gia thấy thông báo cá nhân. Mỗi item có thêm `audience`, `category`. Giữ nguyên các giá trị `type` cũ: `CONSULTATION`, `CONTACT`, `FAMILY`, `REMINDER`, `SYSTEM`; các event tài khoản/thanh toán dùng SYSTEM, không làm normalizer FE cũ loại bỏ item.

Ví dụ request Admin: `GET /api/v1/notifications?audience=ADMIN&category=PAYMENT&limit=20`.

Deep link trả bởi BE:

| Nguồn | Deep link | Route FE hiện có để nối |
|---|---|---|
| USER | `nutrimom://admin/users/{uuid}` | `/admin/users?userId={uuid}` |
| PAYMENT_ORDER | `nutrimom://admin/payment-orders/{orderCode}` | `/admin/revenue?orderCode={orderCode}` (tab doanh thu/thanh toán) |
| CONTACT_REQUEST | `nutrimom://admin/contact-requests/{uuid}` | `/admin/support/{uuid}` |
| CONSULTATION / ADMIN_EXPERT_WAITING | `nutrimom://admin/appointments/{uuid}` | `/admin/appointments?requestId={uuid}` |

PAYMENT_ORDER dùng UUID đơn ở `source_id`, nhưng dùng **orderCode dạng chuỗi số** trong deep link để khớp API chi tiết thanh toán hiện có. FE không ép UUID đối với orderCode, không dùng Number gây mất độ chính xác; chỉ cho phép route theo allowlist và kiểm tra quyền trước khi điều hướng.

## Việc FE cần nối tiếp

Đã đọc `useAdminNotifications.ts`, `admin-notification-link.ts` và normalizer FE; không sửa FE trong task BE này.

1. Các request hộp thư/đếm/read-all của Admin nên thống nhất `audience=ADMIN`; thêm metadata audience/category và filter vào query/cache key. Giữ cursor thuộc filter hiện tại, reset feed khi đổi filter.
2. Mở rộng adapter deep link cho users và payment-orders như bảng trên. Hiện adapter chỉ hỗ trợ hỗ trợ/lịch; nội dung tài khoản/thanh toán vẫn hiển thị được nhưng chưa mở chi tiết được.
3. Chuông và trang thông báo tiếp tục dùng một owner ở AdminLayout. Giữ polling count 60 giây, dừng khi tab ẩn/offline và backoff theo Retry-After cho 429. Không tạo thêm polling timer cho từng component/event.
4. Khi count thay đổi và feed đang mở, refresh trang đầu một lần bằng coordinator/cache hiện có; không fetch từng ID, không tự retry vô hạn. FE hiện cập nhật badge nhưng chưa tự refresh feed theo count.
5. Giữ nội dung cũ trong khi refresh, phân trang cursor, skeleton khi lần đầu tải và chống lặp click read/read-all. Đóng/xóa cache nhạy cảm khi logout, đổi account hoặc nhận 401/403.

Worker thường ghi thông báo trong khoảng 5 giây sau commit; chuông hiện tại có thể cần thêm tối đa một chu kỳ polling 60 giây. Đây chưa phải realtime qua SSE/WebSocket.

## Migration và vận hành

V48 thêm audience/category và index; phân loại các thông báo Admin cũ, không tạo thông báo lịch sử. Thêm bảng outbox và index cho hàng đợi chưa xử lý. Flyway áp dụng khi bản BE mới khởi động trên môi trường cần chạy; task này chỉ áp dụng migration vào PostgreSQL QA localhost.

`NUTRIMOM_ADMIN_NOTIFICATION_JOB_ENABLED=true` mặc định bật job, độc lập với job reminder và metrics. Test profile tắt scheduler; test gọi dispatcher chủ động. Muốn chạy nhiều instance, bật job trên từng instance: SKIP LOCKED + unique key bảo vệ fan-out.

Không cần thêm request dashboard, API mạng xã hội hoặc API cộng đồng cho task này. Số đếm và feed đọc DB, không gọi dịch vụ ngoài.
