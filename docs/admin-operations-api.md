# Admin Operations — bàn giao FE

Nhánh BE: feat/admin-operations-dashboard. Contract mục tiêu chung nằm trong D:/KI8/ADMIN_FE_IMPLEMENTATION_TASK.md và D:/KI8/ADMIN_BE_IMPLEMENTATION_TASK.md.

Đã tích hợp origin/main tại 0b5bb84 (password policy), gồm cập nhật plan catalog/entitlements tại 27a9071 trước đó. Kết quả kiểm thử và giới hạn môi trường: [admin-operations-validation.md](admin-operations-validation.md).

## API đã triển khai

Tất cả đường dẫn dưới đây có prefix /api/v1, yêu cầu Bearer JWT có ADMIN và tài khoản trong DB vẫn ACTIVE, còn vai trò ADMIN. JSON thành công giữ envelope data/meta và snake_case. FE apiClient hiện có đã tháo envelope.

| Endpoint | Query |
|---|---|
| GET /admin/dashboard | fromDate, toDate |
| GET /admin/appointments | page, pageSize, q, status, specialty, expertUserId, fromDate, toDate, dateBasis, attentionOnly |
| GET /admin/appointments/{requestId} | UUID chính xác |
| GET /admin/consultation-requests | q, page, pageSize, fromDate, toDate, specialty, expertUserId, reviewed |
| GET /admin/experts/page | q, page, pageSize, specialty, status |
| GET /admin/revenue/summary | fromDate, toDate, source, range |
| GET /admin/payment-orders | page, pageSize, q, planTier, status, source, fromDate, toDate, dateBasis, range |
| GET /admin/payment-orders/{orderCode} | Mã đơn chuỗi chữ số dương trong phạm vi Long |
| GET /admin/subscriptions | page, pageSize, q, planTier, effectiveStatus, expiringWithinDays |
| GET /admin/reports/{type} | page, pageSize, fromDate, toDate; REVENUE thêm source |
| GET /admin/reports/{type}/export | Cùng bộ lọc báo cáo, không cần page/pageSize |

- Kiểu DTO thực: src/main/java/vn/nutrimom/admin/dto/AdminOperationsDtos.java. Appointment response có thêm updated_at/review/review_rating cho detail và báo cáo; đó là trường bổ sung, mapper FE có thể dùng tùy màn.
- API Consultation lịch sử giữ response cũ, luôn chỉ COMPLETED, thứ tự mặc định created_at DESC/id ASC. Không truyền status để đổi sang lịch đang chờ.
- GET /admin/experts cũ vẫn trả array; FE mới phải dùng /admin/experts/page để tránh tải cả danh sách.
- Các endpoint mới dùng page mặc định1, pageSize20, tối đa100, page tối đa1000000. API cũ giữ giới hạn cũ.
- Metadata tài khoản, lịch, giao dịch được trả theo projection. Không có clinical note, token/phòng video, QR, checkout link hoặc khóa provider.

## Bộ lọc và kiểu dữ liệu

- q trim, tối đa100 ký tự; wildcard %/_ trong tìm kiếm được hiểu là ký tự thường.
- Khoảng ngày phải đủ fromDate/toDate, định dạng YYYY-MM-DD, tính cả hai đầu, tối đa366 ngày theo Asia/Ho_Chi_Minh.
- Dashboard/Revenue/Reports bỏ ngày thì lấy đầu tháng tới hôm nay; không chấp nhận ngày tương lai. Appointments cho đọc lịch tương lai.
- Appointments không có khoảng ngày thì đọc mọi ngày theo phân trang. SCHEDULED lọc ngày slot; CREATED lọc ngày tạo.
- Chờ tiếp nhận: status=PENDING_EXPERT&dateBasis=CREATED. Danh sách chưa hoàn tất: status=PENDING_EXPERT,PENDING_CONSULTATION.
- attentionOnly=true lọc chờ Expert hoặc đã quá giờ kết thúc5phút nhưng chưa hoàn tất; không có trạng thái NO_SHOW/OVERDUE.
- Source: REAL, DEMO, LEGACY_UNKNOWN, ALL (mặc định). Khóa này phản ánh nguồn đã lưu trên đơn, không phụ thuộc cấu hình PayOS lúc đọc.
- order_code của API Admin mới là string. API thanh toán user cũ giữ kiểu số để tương thích. Không ép mã Admin sang Number.
- Amount là số VND chưa format. Tiền thu chỉ PAID có paid_at, dùng amount lưu trên đơn. Dashboard cộng chung ALL nguồn theo yêu cầu chủ dự án.
- Payment list dùng dateBasis=CREATED mặc định hoặc PAID. PAID chỉ lấy đơn đã thanh toán có thời điểm trả tiền.
- effectiveStatus: ACTIVE, EXPIRED, CANCELLED, NOT_STARTED; hiệu lực cần kiểm tra cả ngày và raw status. expiringWithinDays 1..30 chỉ lấy gói đang có hiệu lực.
- Không có row subscription FREE giả; báo cáo người dùng chọn FREE khi không có gói trả phí còn hiệu lực.
- Subscription snapshot có key chính xác plan_99k_users và plan_399k_users; đếm một current plan/người.

## Dashboard

Một GET trả snapshot_at, timezone, period, kpis, attention, today_schedule, series, subscription_snapshot, recent_activity theo contract chung.

Dashboard redesign đã thêm bốn nhóm ngay trong response này, đúng mapper `mapDashboardRedesign` của FE:

- `overview`: `lifetime_real_collected_amount`, `today_booked_appointments`, `today_booking_users`. Tên khóa lifetime_real_collected_amount giữ tương thích FE, nhưng giá trị nay cộng ALL nguồn/PAID có paid_at không vượt snapshot; lịch đặt hôm nay chỉ PENDING_CONSULTATION/COMPLETED, số người đặt là DISTINCT user_id.
- `customer_snapshot`: `as_of` bằng snapshot_at, `total_customers`, `free_users`, `plan_99k_users`, `plan_399k_users`. Chỉ USER không có ADMIN/EXPERT, kể cả trạng thái LOCKED/DISABLED; một gói hiện tại/người, ngày kết thúc exclusive. Tổng ba nhóm bằng tổng khách hàng.
- `monthly_revenue`: `year`, `source=ALL`, `items[{month:YYYY-MM,amount,paid_orders}]` từ tháng 1 tới tháng hiện tại theo giờ Việt Nam; tháng không phát sinh bằng 0, không thêm tháng tương lai.
- `plan_399k_adoption`: `current_ratio_percent`, `previous_ratio_percent`, `change_percentage_points`, `previous_as_of`, `history_started_at`, `comparison_status` và `daily_series[{date,as_of,ratio_percent}]` đủ 30 ngày. Điểm hôm nay dùng snapshot sống; ngày thiếu giữ null; các null được serialize tường minh.
- `today_schedule.hourly_bookings`: 24 phần tử `{hour:"00:00".."23:00",count}` theo giờ Việt Nam, gồm PENDING_CONSULTATION/COMPLETED và loại lịch hủy. Tổng count bằng `overview.today_booked_appointments`; không lấy số từ 6 dòng preview. FE dùng cho mini chart lịch trong cùng GET dashboard.

So sánh 399K dùng snapshot cuối cùng của ngày cuối tháng trước. Tăng/giảm tính theo điểm phần trăm; `AVAILABLE` khi có mẫu so sánh và mẫu số hợp lệ, `INSUFFICIENT_HISTORY` khi thiếu, `NO_CUSTOMERS` khi tổng khách hàng bằng 0. Không suy diễn lịch sử từ subscription đã bị cập nhật hoặc đếm lượt mua làm người đang dùng.

Migration V44__admin_customer_metrics.sql tạo ledger aggregate, không chứa thông tin định danh. Job độc lập Calendar chụp khi ApplicationReady, đầu giờ và 23:59 Việt Nam. Flag `NUTRIMOM_ADMIN_METRICS_SNAPSHOT_ENABLED` mặc định true, test false. Ghi cùng bucket giờ giữ quan sát mới nhất, an toàn khi nhiều instance chạy đồng thời; GET chỉ đọc. Lịch sử bắt đầu khi triển khai, không backfill dữ liệu giả.

`range=ALL_TIME` trên Revenue summary và Payment orders không nhận fromDate/toDate; giá trị khác ngoài PERIOD/ALL_TIME bị từ chối. Summary trả `range=ALL_TIME`, `period=null`, tổng toàn bộ, `previous_collected_amount=0` và `daily_series=[]` để không gọi một đoạn dữ liệu giới hạn là toàn bộ lịch sử. By-plan vẫn là tổng toàn bộ. Payment list `dateBasis=PAID` áp dụng cùng điều kiện paid_at hợp lệ, không vượt hiện tại. FE chỉ dùng summary so sánh kỳ khi không phải ALL_TIME.

- Ba thẻ vận hành là hiện tại/hôm nay; tiền thu và charts theo period.
- Attention tối đa6; today_schedule6; activity8. Counts không bị giới hạn theo preview.
- Registrations chỉ USER, loại tài khoản có ADMIN/EXPERT. Không gọi account ACTIVE là đang online.
- Hai series được điền0 cho ngày không phát sinh. previous period cùng số ngày ngay trước from_date.
- Nếu previous amount/count bằng0, FE ghi chưa có dữ liệu so sánh.
- Một user có nhiều ACTIVE được chọn current plan theo end_date DESC, updated_at DESC, id ASC; log anomaly và không double-count.
- PAID thiếu paid_at không được đoán thời gian hay cộng vào kỳ; BE ghi diagnostic có giới hạn tần suất.

## Reports và CSV

- type: OPERATIONS, USERS_SUBSCRIPTIONS, REVENUE.
- OPERATIONS: cohort yêu cầu tạo trong kỳ, trạng thái tại generated_at; không phải số hủy phát sinh trong kỳ.
- USERS_SUBSCRIPTIONS: cohort user đăng ký trong kỳ, gói tại generated_at; không phải lịch sử gói tại ngày đăng ký.
- REVENUE: PAID trong kỳ theo paid_at và source.
- Report JSON chứa type/generated_at/timezone/period/summary/items/page/page_size/total_items/total_pages.
- CSV UTF-8 BOM, Content-Disposition attachment, số tiền giữ dạng số, text được escape/chặn formula injection.
- CORS expose Content-Disposition để FE đọc filename khi tải CSV từ API khác origin.
- Preview và export dùng cùng query, filter và thứ tự; cap10000 dòng. Vượt cap trả422 trước khi gửi CSV.
- Export đọc theo chunk500 trong transaction REPEATABLE_READ và tạo buffer có giới hạn trước khi trả file, tránh tệp tải thành công nhưng bị cắt nửa do lỗi DB.
- Quota export dùng token bucket hiện có: capacity5/window1phút/principal; 429 có Retry-After. API Admin khác dùng policy default100/phút hiện có.

## Notifications

- Dùng /notifications, /notifications/unread-count, /notifications/{id}/read, /notifications/read-all hiện có; owner luôn là principal. Không mở quyền đọc thông báo của người khác.
- Support mới gửi CONTACT tới Admin ACTIVE, link nutrimom://admin/contact-requests/{id}.
- RANDOM/PENDING_EXPERT mới gửi CONSULTATION tới Admin ACTIVE, source_type=ADMIN_EXPERT_WAITING, link nutrimom://admin/appointments/{id}.
- Event dedup theo recipient + loại sự kiện + source qua unique dedup_key. Atomic upsert tránh push trùng hoặc rollback do duplicate.
- Mark-read của từng Admin độc lập, không đổi trạng thái nguồn. Mutation Support thành công cần invalidate dashboard/Support liên quan; không clear toàn bộ cache ứng dụng.

## Lỗi và môi trường

- 401 thiếu/hết phiên; 403 thiếu quyền/tài khoản không còn ACTIVE/ADMIN; 404 không có tài nguyên; 409 version conflict; 400 định dạng sai; 422 ràng buộc/filter/cap; 429 quota.
- Giữ error.code/message/fields/retryable/request_id. FE không retry403/422, CSV429 tôn trọng Retry-After.
- Không có endpoint User được mở rộng thành Admin. Admin không được vào phòng User–Expert.
- Migration mới: V42__admin_operations.sql, V43__consultation_schedule_snapshot.sql, V44__admin_customer_metrics.sql , V45__payment_source_legacy_writer_compatibility.sql , V46__recognize_subscription_upgrades.sql và V47__subscription_revenue_safe_order_codes.sql.
- V45 xử lý writer cũ chưa ghi source: UNKNOWN có link MOCK-* thành DEMO; link PayOS dạng 32 ký tự hex thành REAL. Không suy nguồn từ PAID hoặc checkout URL đơn thuần, không thay nguồn REAL/DEMO đã xác định. Backfill tăng version, giữ nguyên amount/paid_at/subscription; trigger áp dụng cho các lượt insert/update link mới.
- Slot snapshot được lưu khi đặt/nhận lịch và còn sau khi hủy; không giữ trạng thái BOOKED của slot đã giải phóng. Dữ liệu cũ còn slot_id được backfill; các lịch đã hủy từ trước và mất slot_id không thể đoán lại ngày, nên slot vẫn null và có thể xem bằng CREATED/Tất cả.
- Demo thanh toán giờ cần NUTRIMOM_PAYMENT_DEMO_ENABLED=true explicit; mặc định false. Không đặt flag này trên production.
- confirm-mock chỉ owner, source DEMO, server bật demo, trạng thái PENDING hoặc PAID đã xử lý. Public unsigned webhook không kích hoạt được đơn.
- GET Admin không gọi PayOS và không tự sửa giao dịch/gói. Webhook provider được mock khi test; chưa gửi giao dịch thật.
- Không có thay đổi code FE trong nhánh BE này. Không cần FE thêm request để chạy animation.


## Total revenue thống nhất — cập nhật 13/10/2026

- Total revenue, Revenue Overview, tiền thu/ngày, trang doanh thu và báo cáo REVENUE mặc định cộng mọi nguồn đã ghi nhận. Source vẫn là metadata nội bộ để tương thích API; không phân loại gói cấp bởi dev trong giao diện doanh thu.
- Đơn PAID có paid_at hợp lệ và không vượt snapshot được tính theo amount đã lưu. PENDING, thiếu paid_at và paid_at tương lai không được cộng. Ví dụ ba đơn 399K hiện tại cho tổng 1.197.000đ, dù hai gói có thời hạn 180 ngày.
- V46 ghi thêm đúng một dòng ledger khi customer được đổi FREE -> 99K/399K hoặc được kích hoạt gói trả phí mới trong PostgreSQL, kể cả writer không đi qua API. Giá danh nghĩa 99.000/399.000đ; không nhân theo số ngày. Không sửa subscription hoặc tạo link/giao dịch PayOS.
- PaymentService flush đơn PAID trước subscription. Trigger nhận diện đơn cùng transaction để không cộng lần thứ hai; khóa user để tuần tự hóa các insert subscription chồng lấn. Ghi nhận lại cùng tier đang ACTIVE, sửa thời hạn hoặc xuống FREE không tạo doanh thu mới. Một đơn mua/gia hạn PayOS riêng vẫn được tính như trước.
- Backfill chỉ cấp một ledger cho current paid subscription chưa có bất kỳ đơn PAID hợp lệ cùng user/tier. Ba user hiện tại đã có đơn nên không sinh thêm dòng. Không thể tái dựng toàn bộ lịch sử chỉnh DB trước khi trigger được cài.
- Không tự trừ doanh thu khi gói hết hạn/xuống FREE; đây là tổng ghi nhận lũy kế. Hoàn tiền cần luồng riêng.
- Không thêm request, polling hay gọi PayOS từ API Admin; vẫn một dashboard GET với cache/dedupe 30 giây. Chỉnh DB phản ánh trong lần đọc mới; có thể bấm Làm mới để bỏ cache.

- V47 giới hạn sequence mã đơn ghi nhận ở vùng số nguyên JavaScript an toàn, bảo toàn API user cũ đang trả Long dạng number. Admin vẫn dùng string cho order_code.

## So sánh bốn KPI — cập nhật 14/10/2026

- `GET /admin/dashboard` bổ sung `metric_comparisons`: `previous_month` (YYYY-MM), `booking_baseline_days` (30), cùng bốn cặp `current_value`/`previous_value`: `revenue`, `customers`, `appointments`, `plan_399k`. Các field cũ giữ nguyên để FE tương thích.
- Revenue: tổng PAID hợp lệ đến snapshot so với tổng trước 00:00 ngày đầu tháng hiện tại. Giữ cả doanh thu các năm trước, cùng chính sách Total revenue thống nhất.
- Customers: số customer USER đăng ký từ đầu tháng đến snapshot so với toàn bộ tháng trước; loại ADMIN/EXPERT. Giá trị lớn trên ô vẫn là tổng khách hàng.
- Appointments: lịch PENDING_CONSULTATION/COMPLETED hôm nay so với trung bình 30 ngày lịch liền trước, không tính hôm nay, bao gồm ngày không có lịch; loại CANCELLED. Mốc ngày theo Asia/Ho_Chi_Minh.
- Plan 399K: tỷ lệ đang dùng hiện tại so với snapshot quan sát cuối tháng trước. Không dựng lại lịch sử từ gói hiện tại; thiếu snapshot trả null.
- Dùng các aggregate đã có cho doanh thu, khách hàng và lịch; không thêm endpoint, truy vấn đọc, polling hoặc request FE.
- FE hiển thị phần trăm `(current - previous) / previous * 100` nếu previous > 0. Previous = 0 và current > 0 hiển thị “Mới”; cả hai bằng 0 hiển thị 0,0%; thiếu lịch sử hiển thị “—”. Chỉ tăng dương là xanh, bằng 0/giảm là đỏ. Với 399K, dòng chênh lệch tuyệt đối dùng điểm phần trăm.
- Kiểm chứng: 16 bài AdminOperations/Redesign PASS, gồm tháng Việt Nam, giao năm, kỳ thống kê độc lập, ngày trống và lịch hủy. API localhost PostgreSQL trả nhóm so sánh mới thành công; doanh thu 1.197.000đ.
- Kiểm thử: PostgreSQL 21 lượt ở subscription-revenue-postgres.log; vòng cuối PaymentSafety + SubscriptionRevenue 14 lượt ở subscription-revenue-postgres-final.log; SubscriptionRevenue với V47 7 lượt ở subscription-revenue-safe-codes.log, tất cả PASS. AdminOperations + dashboard H2 14 lượt PASS; FE 39 lượt PASS; cả hai build PASS.
