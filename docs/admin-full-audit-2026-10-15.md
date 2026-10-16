# Rà soát Admin FE + BE — 15/10/2026

Nhánh BE: `feat/admin-operations-dashboard`. Nhánh FE: `feat/admin-workspace-dashboard`.

Các màn hình Admin đang có đã nối API; không phát hiện màn hình Dashboard nào còn thiếu API đọc bắt buộc. Đợt audit có sửa lỗi FE, tính nhất quán doanh thu và một điểm nghẽn truy vấn BE. Điều này không đồng nghĩa mọi tính năng của một hệ thống quản trị đầy đủ đã được xây dựng, hoặc đã đo tải production.

## Đã có và còn thiếu

| Khu vực | Hiện trạng đã kiểm tra | Phần chưa xây dựng |
| --- | --- | --- |
| Dashboard | 4 KPI, so sánh kỳ, doanh thu tháng, cơ cấu gói, việc cần xử lý, lịch hôm nay, kỳ thống kê gộp, hoạt động gần đây, gói còn hiệu lực; một API snapshot | Chưa có stream đẩy cập nhật liên tục; làm mới theo thao tác/focus khi cache hết hạn/ngày Việt Nam thay đổi |
| Users | Danh sách có phân trang, tìm kiếm/lọc, tổng hợp, chi tiết tài khoản | Chưa có thao tác Admin khóa/mở khóa, đổi role hay reset mật khẩu |
| Experts | Tạo, sửa, vô hiệu hóa, avatar, danh mục chuyên khoa; kiểm tra version chống ghi đè | Không có bảng điều phối công suất chuyên gia riêng |
| Appointments | Danh sách, lịch, chi tiết, trạng thái, hàng cần ưu tiên; bảo lưu thời gian lịch | Chưa có thao tác Admin phân công lại chuyên gia, đổi/hủy lịch; không coi chức năng của user/expert là chức năng của Admin |
| Lịch sử tư vấn | Danh sách và chi tiết buổi đã hoàn tất | Không có quyền Admin truy cập ghi chú lâm sàng/phòng gọi riêng |
| Support | Danh sách, chi tiết, hoàn tất yêu cầu; thông tin liên hệ có thể chỉ có email | Chưa có chat trả lời hai chiều, phân công nhân viên hoặc quản lý SLA riêng |
| Knowledge | CMS bài viết, trạng thái và media thật | `/admin/health`, `/admin/nutrition` hiện chuyển về CMS; chưa có hai module quản lý chuyên biệt |
| Revenue & subscriptions | Tổng hợp, giao dịch, chi tiết đơn, danh sách gói/hiệu lực; nguồn mặc định ALL theo yêu cầu gộp doanh thu | Chưa có hoàn tiền, màn đối soát cổng thanh toán hoặc quản lý cấp/thu hồi gói riêng |
| Reports | Xem trước và CSV cho vận hành, người dùng/gói, doanh thu; giới hạn 10.000 dòng | Chưa có báo cáo tùy biến hoặc xuất nền cho dữ liệu vượt giới hạn |
| Notifications | Chuông + hộp thư; nhóm ACCOUNT/PAYMENT/APPOINTMENT/SUPPORT, phân trang cursor, đọc từng mục/đọc tất cả, deep link | Chưa có SSE/WebSocket/browser push; thay đổi trực tiếp bằng SQL không tự tạo sự kiện thông báo API |
| Settings | Route cũ chuyển về Dashboard | Chưa có trang cấu hình Admin thực sự |
| Social followers / Cộng đồng cho mẹ | Chưa có module tích hợp | Cần BE + FE và thiết kế quyền/moderation riêng nếu triển khai; không phải chức năng Dashboard hiện tại |

13 sự kiện thông báo BE: tạo/kích hoạt/vô hiệu hóa tài khoản; thanh toán/gia hạn; tạo/hoàn tất/hủy hỗ trợ; tạo lịch/chờ chuyên gia/tiếp nhận/hoàn tất/hủy lịch. Outbox dùng transaction nghiệp vụ và chống trùng theo người nhận. Sửa gói trực tiếp trong PostgreSQL vẫn được ghi nhận doanh thu bằng migration V46–V47 theo yêu cầu trước đó; không suy diễn thao tác SQL thành sự kiện thông báo.

## Những lỗi đã sửa trong đợt audit

1. **Support chỉ có email:** trường phone nullable trước đây có thể làm trang chi tiết crash khi gọi `.replace()`. FE đã chấp nhận nullable và hiển thị “Chưa cung cấp”.
2. **Hoàn tất hỗ trợ bị gửi trùng/ghi đè:** thêm khóa submit tức thời, tách instance theo requestId, chặn cập nhật sau unmount. Phản hồi yêu cầu A đến muộn không thể ghi đè yêu cầu B.
3. **Chart không kết thúc trạng thái animation:** timer dự phòng được lập lại khi Recharts phát `onAnimationStart`, kể cả khi thiếu callback kết thúc. Không thêm request vì animation.
4. **429 ở thao tác ghi Admin:** thao tác ghi dùng cùng cooldown của nhóm Admin; không gửi tiếp request trong thời gian chờ. Lỗi của phiên cũ không được khóa phiên mới.
5. **CSV và phiên đăng nhập:** cooldown xuất báo cáo được giữ theo phiên qua chuyển trang; nút thể hiện thời gian chờ. Phiên bị 401 cuối cùng được xóa; lỗi không tự tải lại CSV.
6. **Tổng doanh thu không cùng snapshot:** cả hai overload revenue dùng transaction REPEATABLE_READ; tổng, chuỗi ngày và nhóm gói không bị lệch nếu giao dịch mới commit giữa các SELECT.
7. **Swagger mặc định sai nguồn:** schema default đổi từ REAL sang ALL, khớp hành vi service và yêu cầu gộp doanh thu.
8. **Hoạt động gần đây chậm trên bảng lớn:** trước đây UNION đọc toàn bộ từng nguồn rồi mới LIMIT 8. Nay mỗi nguồn lấy tối đa 8 bản ghi đúng thứ tự, sau đó ghép tối đa 32 ứng viên và lấy 8 cuối cùng. Migration **V49** bổ sung index khớp timestamp DESC / id ASC, kể cả trường hợp nhiều bản ghi cùng timestamp. Test so sánh kết quả với UNION đầy đủ trên cả bốn nguồn.
9. **Hạ tầng test H2:** các Spring context trước đây dùng chung DB create-drop; context thất bại/được đóng có thể xóa schema của context khác. Test profile đã dùng DB riêng theo context; không đổi cấu hình DB ứng dụng.

Không đổi yêu cầu giao diện hiện tại: màu tím đặc theo theme, ring tím đậm/nhạt với nhãn “Tổng”, tooltip nằm ngoài vòng tròn, tỷ lệ 399K theo tổng khách hàng hiện tại, animation nhẹ khi quay lại trang. Các URL Health/Nutrition/Settings không được tính là module đã hoàn thiện.

## Hiệu suất và quyền truy cập

| Thao tác FE | Số request nghiệp vụ đã kiểm tra bằng fixture |
| --- | --- |
| Vào Dashboard | 1 GET dashboard + 1 GET số thông báo chưa đọc; không tính login/refresh token |
| Vẽ/resize/hover chart, đổi theme hoặc đồng hồ phút | 0 GET dashboard phát sinh |
| Sửa ngày nháp / Apply | 0 / 1 GET; Apply lại cùng kỳ không gọi lại |
| Bấm Refresh Dashboard hai lần liên tiếp | 1 GET |
| Quay lại Dashboard khi cache còn mới | 0 GET và không chạy lại số |
| Tìm kiếm liên tiếp / đổi trang | Debounce thành 1 GET danh sách; không tải lại summary không liên quan |
| Vào Revenue / chuyển trang / chuyển tab gói | 2 GET ban đầu; sau đó chỉ GET danh sách tương ứng |
| Mở chuông lần đầu | 1 GET danh sách; chia sẻ cache với hộp thư cùng bộ lọc |
| Chuông đóng, tab đang hoạt động | Count theo chu kỳ 60 giây; không polling danh sách khi đóng |
| Hoàn tất Support | 1 POST; dùng response cập nhật chi tiết, không GET lại chi tiết/count ngay |
| Xuất CSV | 1 GET có token; không tự retry lỗi, quota/cooldown riêng với danh sách |

Coordinator cache có giới hạn, khóa theo phiên/người dùng, gom request đang chạy, hủy lease khi unmount và chặn phản hồi cũ. List thông báo cache 30 giây, count 60 giây; giảm/tạm dừng hoạt động khi offline/ẩn trang. Outbox BE chạy theo nhịp mặc định 5 giây; count trên FE có thể chậm hơn sự kiện đến một nhịp polling. Không gọi đó là realtime tức thời.

BE bảo vệ API bằng JWT role và quyền ADMIN còn hiệu lực trong DB; token có role cũ không vượt qua tài khoản đã khóa/thu hồi role. `audience=ADMIN` là bộ lọc thông báo, không phải quyền tự cấp bởi FE. Validation bao gồm ngày/khoảng ngày, enum, page/pageSize, UUID, orderCode dạng chuỗi, mật khẩu và version khi ghi. DTO Admin không trả ghi chú y tế/phòng gọi riêng.

**Đo PostgreSQL sau tối ưu:** 10.000 người dùng + 10.000 lịch + 10.000 đơn PAID; 3 lần warm-up và 30 mẫu. Dashboard service p95 **133 ms**, **19 logical read calls** mỗi snapshot, phần preview bị chặn theo số lượng. Đây là thời gian tầng service/DB trên máy QA, không phải HTTP p95 trên production hay kết quả load test nhiều người dùng đồng thời. Trước sửa, benchmark bị nghẽn ở truy vấn recent activity; lượt chạy đó bị hủy, không tính là kết quả pass.

## Kiểm chứng

- FE lint: sạch, không warning; unit/runtime tests: **392/392 pass**; production build thành công.
- BE Admin và nghiệp vụ liên quan: **113 test, 112 pass, 1 CMS HTTP multipart skip** đã có do môi trường localhost Windows. Build JAR thành công. Javac `-Xlint:all -Werror` cho package Admin: pass.
- PostgreSQL QA độc lập: **37/37 pass** gồm benchmark, snapshot doanh thu khi commit đồng thời, thanh toán, cấp gói qua DB, compatibility source, thông báo và migrate V48→V49/idempotence.
- Browser QA workspace: **23 tình huống fixture pass**, không runtime exception; đăng nhập BE thật không được tính pass khi không cấp credentials QA. Kiểm tra CSV có BOM/filename/token, cooldown qua remount, email-only Support, double-submit và A→B race.
- Browser Dashboard: **10/10 tình huống fixture pass**, gồm animation nhanh/chậm, viewport 360–1440, theme, reduced motion, cache, 429, rollover ngày Việt Nam, contract thiếu/sai và 403. Kiểm tra geometry bổ sung xác nhận SVG lấp đầy vùng vẽ và tooltip FREE nằm ngoài vòng tròn.
- Browser Notifications: **23/23 fixture pass**, không runtime exception; bao gồm một lượt chờ thực hơn 60 giây xác nhận chuông đóng chỉ gọi count một lần, không gọi list.
- BE toàn dự án, chạy lại sau tách DB test và thử quyền thực thi mở rộng: **502 test; 299 pass, 0 assertion failure, 185 error, 18 skip**. Các error còn lại gồm Java HTTP client không thiết lập được loopback và Tomcat không khởi động được connector, kéo theo Spring context failure. Chưa chứng nhận toàn bộ BE xanh. Lượt trước khi tách DB có thêm 10 assertion failure do schema H2 bị context khác xóa; không coi những lỗi đó là kết quả cuối.

Log nằm tại `BE/target/admin-audit-2026-10-15-*.log`, `FE/qa/admin-workspace/audit-2026-10-15-*.log`, `FE/qa-artifacts/admin-audit-2026-10-15/`. Đây là dữ liệu fixture/DB QA, không phải dữ liệu mua hàng thực.

## Phần cần làm tiếp

Ưu tiên vận hành tiếp theo: khóa/mở khóa user có audit log; sau đó điều phối lịch và đối soát thanh toán nếu cần các thao tác này trong Admin. Health/Nutrition riêng, cộng đồng và social followers là các module mới, không chỉ thêm UI.

Về bảo trì: cần quyết định thời gian lưu/archival của outbox và thông báo, giám sát backlog/retry, rồi đo HTTP/load test trên staging với dữ liệu đại diện. Dashboard tháng hiện trả T1 đến tháng đang diễn ra; chưa hiển thị cột tương lai không có doanh thu.

Khi triển khai BE phải đưa **V49** đi cùng code truy vấn mới; áp dụng migration bằng Flyway trong quy trình deploy. Đợt audit chỉ chạy migration trên PostgreSQL QA riêng. Chưa push, deploy hay sửa dữ liệu tài khoản thật.

Lượt kiểm tra này không chạy lại thanh toán PayOS thật, không truy cập/chỉnh tài khoản khách hàng và không dùng fixture để tuyên bố FE→BE production đã được kiểm chứng. Cần smoke test trên staging có BE đang chạy, DB đã migrate, tài khoản Admin QA và webhook PayOS hợp lệ trước khi chốt deploy.

FE build còn cảnh báo chunk `LiveKitCallRuntime` khoảng 650 kB (175 kB gzip). Đây là phần gọi video được lazy-load, không tải lúc mở Admin Dashboard; vẫn là công việc tối ưu của luồng gọi video, không sửa bằng cách nâng ngưỡng cảnh báo để che nó.
