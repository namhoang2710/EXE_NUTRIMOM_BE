# Đối chiếu FE Admin và BE local — 13/10/2026

BE: `feat/admin-operations-dashboard`, HEAD `0b5bb84` cùng thay đổi cục bộ chưa commit/push. FE: `feat/admin-workspace-dashboard` cùng code hiện tại trong workspace.

## Kết luận

Không tìm thấy endpoint còn thiếu để phục vụ FE Admin đang triển khai. Không cần API riêng cho redesign KPI, chart hoặc animation. Dashboard dùng cùng một GET và FE cache/deduplicate request.

| Nhóm FE | BE đã có |
|---|---|
| Dashboard | GET /admin/dashboard: overview, customer_snapshot, monthly_revenue, plan_399k_adoption, hourly_bookings; giữ attention/today_schedule/series/recent_activity |
| Người dùng | Danh sách, summary, chi tiết |
| Chuyên gia | Danh sách phân trang, chi tiết, tạo/sửa/ngừng hoạt động, upload avatar, specialties |
| Lịch & lịch sử | Danh sách/bộ lọc lịch, chi tiết, lịch sử COMPLETED và đánh giá |
| Hỗ trợ | Danh sách, chi tiết, đánh dấu hoàn tất |
| Kiến thức | Danh sách, chi tiết, tạo/sửa/xóa bài, upload media |
| Doanh thu & gói | Summary PERIOD/ALL_TIME, giao dịch/drawer, danh sách gói còn/hết hiệu lực |
| Báo cáo | OPERATIONS, USERS_SUBSCRIPTIONS, REVENUE và CSV export |
| Thông báo | Danh sách, số chưa đọc, đọc từng thông báo và đọc tất cả |

Các route /admin có JWT ADMIN và ActiveAdminFilter kiểm tra tài khoản/role hiện tại trong DB. Notifications là API dùng chung, áp dụng owner của principal; từ V48, thông báo audience ADMIN còn kiểm tra quyền ADMIN/ACTIVE hiện tại kể cả khi JWT cũ chưa hết hạn. Xem [Admin notifications](admin-notifications.md) cho event, filter và phần FE cần nối tiếp. Không mở quyền đọc hồ sơ bệnh án/phòng tư vấn riêng tư.

## Phạm vi còn chưa triển khai riêng

- FE /admin/health và /admin/nutrition chuyển sang /admin/knowledge. Đây là quản lý nội dung qua CMS, chưa phải hai module quản trị riêng.
- FE /admin/settings chuyển về /admin. Chưa có màn/API cấu hình hệ thống riêng.
- Người dùng và lịch hiện chủ yếu phục vụ xem/lọc/chi tiết. Các nghiệp vụ mới như khóa/mở khóa user hoặc phân công/hủy lịch bởi Admin cần được xác định thêm và triển khai cả FE + BE; chúng không phải endpoint mà FE hiện tại đang gọi nhưng bị thiếu.

## Xác minh

- Đối chiếu source FE, controllers/DTO và OpenAPI của BE local: 34 tổ hợp method/path yêu cầu đều tồn tại.
- 24 lượt GET HTTP local thành công. Dùng mapper FE thực cho dashboard, hourly bookings, appointments, completed history, revenue, payments, subscriptions, reports; các endpoint Users/Experts/Support/CMS/Notifications kiểm tra shape thiết yếu. Không lưu payload thật, credential/token hoặc thông tin người dùng.
- Mutations/uploads/CSV có trong OpenAPI và source; không thực hiện mutation/upload/export trên database local trong lượt audit này.
- Phiên đăng nhập kiểm tra riêng đã logout. Không thay phiên của trình duyệt.
- 9 suites BE: 71 cases, 70 passed, 0 failed/error, 1 skipped (multipart HTTP thực trong môi trường hạn chế; CMS còn được kiểm tra bằng MockMvc).
- Maven package thành công. Logs: target/admin-fe-audit-tests.log và target/admin-fe-audit-package.log. HTTP report không chứa dữ liệu cá nhân: FE qa/admin-workspace/admin-be-audit-results.json.

## Trước khi triển khai

Đưa đầy đủ file mới vào commit, gồm controllers/services/repositories/security filter/config và migrations V42/V43/V44; commit chỉ các file tracked sẽ bỏ sót nhiều phần quan trọng. Deploy BE có migrations này trước hoặc đồng thời với FE. Ledger 399K cần thời gian tích lũy lịch sử, nên INSUFFICIENT_HISTORY ở giai đoạn đầu là hợp lệ.

Lượt này chỉ kiểm tra/build và viết kết quả audit, không thay code API, không commit/push/deploy. Không tuyên bố đã kiểm tra E2E mọi thao tác ghi hoặc toàn bộ suite repository.
