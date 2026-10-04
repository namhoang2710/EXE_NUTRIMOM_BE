# Phòng tư vấn LiveKit

Frontend có phòng gọi 1–1 tại `/app/consultations/:id/call` và `/expert/consultations/:id/call`.
Backend kiểm tra người đặt và chuyên gia được phân công, trạng thái tài khoản/chuyên gia,
trạng thái lịch và cửa sổ giờ Việt Nam trước khi cấp token. Phòng mở sớm 5 phút và đóng
5 phút sau khung 30 phút; có thể đổi `app.consultation.video.join-early/end-grace`.

## Kích hoạt

1. Tạo project Build tại https://cloud.livekit.io (không cần thẻ, hạn mức miễn phí).
2. Trong Settings → Keys, lấy URL `wss://….livekit.cloud`, API key và secret.
3. Điền vào **EXE_BE/.env**, không đặt secret trong FE hoặc commit vào Git:

   ```dotenv
   NUTRIMOM_VIDEO_ENABLED=true
   LIVEKIT_URL=wss://your-project.livekit.cloud
   LIVEKIT_API_KEY=your-api-key
   LIVEKIT_API_SECRET=your-api-secret
   ```

4. Khởi động lại BE bằng cách chạy hiện có của dự án. Flyway chạy V36 tự động.
5. Trong LiveKit Settings → Webhooks, thêm URL HTTPS công khai của BE:
   `https://<backend-domain>/api/v1/consultation-video/webhook` và chọn **cùng API key**.
   Localhost không nhận được webhook từ cloud; dùng HTTPS tunnel khi thử tại máy.
   Webhook ghi nhận tham gia, không tự hoàn tất lịch. Gọi không phụ thuộc vào webhook.
6. Đăng nhập người đặt/chuyên gia trên hai trình duyệt hoặc hai máy. Đặt/nhận lịch,
   mở lịch sử/lịch chuyên gia → **Phòng tư vấn** → kiểm tra thiết bị → **Vào phòng**.

## Quyền và vòng đời

- GET `/api/v1/consultation-requests/{id}/video`: thông tin phòng, giờ server, khả năng vào.
- POST `…/{id}/video/join`: token giới hạn phòng/người, TTL tối đa 5 phút, khóa E2EE.
- POST `…/{id}/video/complete`: chuyên gia được phân công xác nhận hoàn tất.
- POST `/api/v1/consultation-video/webhook`: xác minh HS256, issuer, hạn và SHA256 raw body.
- Phòng chỉ chứa 2 người; client không có quyền tạo/list/admin/ghi hình hoặc gửi data.
- Token/khóa E2EE chỉ giữ trong RAM, không có trong URL hay localStorage. Khóa phòng
  được dẫn xuất bằng HMAC từ secret của BE; không đổi secret khi buổi gọi đang diễn ra.
- React dùng E2EE, worker riêng và VP8 với camera giới hạn bitrate; trình duyệt cần
  hỗ trợ E2EE và HTTPS (localhost là ngoại lệ cho thử nghiệm).
- Rời phòng/mất mạng không hoàn tất lịch. Khi người có quyền hủy/hoàn tất, dữ liệu lịch
  commit trước; BE thu hồi token của cả hai và đóng phòng. Nếu provider lỗi, dòng chưa
  cleanup được lưu để thử lại mỗi 15 giây. Token hết hạn không tự ngắt phiên đã kết nối.
- Scheduler đóng phòng quá giờ hoặc khi tài khoản/chuyên gia không còn hoạt động.
- Không ghi hình/phiên âm, không gửi hồ sơ sức khỏe hoặc ghi chú đặt lịch vào metadata provider.
- Nếu chưa cấu hình, API thông tin vẫn hoạt động và FE hiển thị phòng chưa sẵn sàng.

## Hạn mức và kiểm thử thực tế

Build có 5.000 participant minutes và 50 GB truyền ra/tháng tại thời điểm 03/10/2026.
Theo dõi cả hai tại LiveKit dashboard; thời gian chờ trong phòng cũng tính phút.
Không có bộ đếm chi phí chính xác trong ứng dụng; dashboard của provider là nguồn đối chiếu.
Hết hạn mức hoặc provider lỗi hiển thị lỗi thử lại, không xóa lịch hay tự đánh dấu hoàn tất.

Kiểm tra tự động hiện tại dùng H2 và LiveKit giả lập ở BE, API giả lập và thiết bị
camera/micro giả trong Chrome ở FE. Đã kiểm tra quyền, giờ vào, hủy/hoàn tất,
hai người vào đồng thời, retry cleanup, chữ ký webhook và giao diện.
Chưa kiểm thử cuộc gọi qua LiveKit Cloud hoặc migration V36 trên PostgreSQL thực tế.

Trước khi dùng thực tế: thử Wi-Fi/4G, hai máy, camera bị từ chối, nghe bằng tai nghe,
chia sẻ màn hình, mất mạng/kết nối lại, hủy/hoàn tất từ tab khác, vào bằng tài khoản khác,
quá giờ và token cũ sau thu hồi. Kiểm thử cloud cần project/key thật.

Tài liệu: https://docs.livekit.io/deploy/admin/quotas-and-limits/,
https://docs.livekit.io/frontends/reference/tokens-grants/,
https://docs.livekit.io/transport/encryption/.
