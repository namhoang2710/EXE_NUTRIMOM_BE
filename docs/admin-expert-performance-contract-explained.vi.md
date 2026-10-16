> **Phiên bản mới:** xem [Contract KPI v3](admin-expert-performance-v3-contract.vi.md): chuyên cần theo lịch làm việc, đúng giờ theo lần bấm vào phòng đầu tiên. Các ví dụ bên dưới mô tả công thức lịch sử v1.

# Contract KPI chuyên gia — công thức, dữ liệu và cách đọc kết quả

Cập nhật: 15/10/2026, giờ Việt Nam. Đối chiếu implementation trên nhánh `feat/admin-operations-dashboard`.

Tài liệu này giải thích nghiệp vụ và hợp đồng dữ liệu đang được BE thực hiện. Các số ở mục 1 đã được đối chiếu bằng truy vấn SQL chỉ đọc, dùng repository BE thật và DB cấu hình của dự án, lúc **17:27:22 ngày 15/10/2026 giờ Việt Nam** (10:27:22 UTC). Phạm vi: toàn đội, kỳ 01–15/10 và kỳ trước 16–30/09. Không sửa dữ liệu, không xuất danh tính hoặc thông tin kết nối. Đây là số đếm bản ghi trong DB tại snapshot này, không xác nhận nguồn gốc hoạt động thực tế của từng bản ghi. Những ví dụ khác được ghi là minh họa.

## 1. Đọc đúng các số trong ảnh

Kỳ được chọn: **01/10/2026–15/10/2026**, xem **toàn đội ngũ**.

| KPI | Phép tính kỳ hiện tại | BE trả | Chart có thể hiển thị | Kỳ trước đã đối chiếu DB | Hiện tại trừ kỳ trước |
| --- | --- | --- | --- | --- | --- |
| Chuyên cần | 25 / 28 × 100 | 89,29% | 89,3% | 4/4 = 100% | 89,29 − 100 = **−10,71 điểm phần trăm** |
| Đúng giờ | 12 / 28 × 100 | 42,86% | 42,9% | 2/4 = 50% | 42,86 − 50 = **−7,14 điểm phần trăm** |
| Hài lòng | 11 / 11 × 100 | 100% | 100% | 3/3 = 100% | 100 − 100 = **0 điểm phần trăm**, không đổi |
| Hoàn thành | 25 / 28 × 100 | 89,29% | 89,3% | 4/4 = 100% | 89,29 − 100 = **−10,71 điểm phần trăm** |

Diễn giải:

- Có **28 lịch đủ điều kiện đã đến hạn**, không phải 28 expert hay 28 tài khoản.
- Trong đó **25 lịch đạt chuyên cần** và **25 lịch đã hoàn thành**. Hai số này đang bằng nhau trong ảnh; công thức của chúng vẫn khác nhau.
- Có **12/28 lịch hoàn thành trước hoặc đúng giờ kết thúc đã hẹn**.
- Có **11 đánh giá hợp lệ**, cả 11 đều là 4 hoặc 5 sao. Vì vậy hài lòng là 100%, dù có 25 lịch hoàn thành. Không lấy 11 chia cho 28 để tính hài lòng.
- Theo các số trong ảnh, 25 − 11 = **14 lịch hoàn thành chưa có đánh giá được tính**. Chúng không tự biến thành đánh giá xấu.

**Đã xác minh số kỳ trước từ DB:**

| Số đếm 16–30/09/2026 | Kết quả |
| --- | --- |
| Lịch đủ điều kiện đã đến hạn | 4 |
| Lịch đạt chuyên cần theo quy tắc BE | 4 |
| Lịch hoàn thành | 4 |
| Lịch hoàn thành trước/đúng giờ kết thúc | 2 |
| Đánh giá hợp lệ | 3 |
| Đánh giá 4–5 sao | 3 |

Do đó, 100% chuyên cần và hoàn thành xuất phát từ **4/4**, 50% đúng giờ từ **2/4**, 100% hài lòng từ **3/3**. Đây không phải mức chuẩn cố định, không phải số mẫu FE và kỳ trước không có cùng mẫu số 28 như kỳ hiện tại. Bốn lịch kỳ trước đều hoàn thành; hai lịch hoàn thành sau giờ kết thúc nên không đạt đúng giờ. Một lịch hoàn thành chưa có đánh giá được tính, không tự xem là đánh giá xấu.

BE xác định kỳ này bằng **ngày hẹn 16–30/09**, không bằng ngày tạo user hoặc ngày mua gói. Trạng thái và timestamps được đọc tại snapshot nói trên; dữ liệu thay đổi có thể làm số đọc sau khác đi.

## 2. Kỳ trước là khoảng ngày nào?

BE chọn **khoảng liền trước có cùng số ngày**, tính cả ngày đầu và ngày cuối.

```text
N = số ngày từ fromDate đến toDate, bao gồm hai đầu
previous_to_date   = fromDate − 1 ngày
previous_from_date = fromDate − N ngày
```

Ví dụ:

| Kỳ đang chọn | Số ngày | Kỳ trước mà BE sử dụng |
| --- | --- | --- |
| 01/10/2026–15/10/2026 | 15 | 16/09/2026–30/09/2026 |
| 10/10/2026–15/10/2026 | 6 | 04/10/2026–09/10/2026 |
| 15/10/2026–15/10/2026 | 1 | 14/10/2026–14/10/2026 |

Vì vậy, ở ảnh **kỳ trước không phải toàn bộ tháng 9**, cũng không phải 01–15/09. Khi số ngày trong hai tháng khác nhau, khoảng so sánh có thể vượt qua ranh giới tháng; FE phải hiển thị ngày do BE trả.

Các khoảng ngày dùng `Asia/Ho_Chi_Minh`. Bộ lọc chuyên khoa/expert đang áp dụng được giữ cho cả hai kỳ.

## 3. “100% kỳ trước” và dấu cộng/trừ nghĩa là gì?

Mỗi kỳ được tính tỷ lệ độc lập từ dữ liệu của kỳ đó:

```text
percent = làm tròn 2 chữ số thập phân (numerator / denominator × 100)
change_percentage_points = current.percent − previous.percent
```

- Số dương: tỷ lệ hiện tại cao hơn kỳ trước.
- Số âm: tỷ lệ hiện tại thấp hơn kỳ trước.
- Số 0: tỷ lệ bằng kỳ trước. UI ghi `+0` vẫn có nghĩa là **không đổi**.
- `null`: chưa đủ điều kiện so sánh; không được diễn giải là không đổi.

**Kỳ trước 100% là kết quả dữ liệu, không phải KPI mục tiêu hay số được gán sẵn.** Ví dụ minh họa: kỳ trước có 2 lịch, cả 2 đạt thì 2/2 = 100%; kỳ này có 28 lịch, 25 đạt thì 25/28 = 89,29%. Tỷ lệ giảm, dù số lịch đạt đã tăng từ 2 lên 25. Hai con số đo hai khía cạnh khác nhau.

### Điểm phần trăm khác phần trăm tăng trưởng

Ví dụ minh họa: kỳ trước 50%, kỳ này 40%.

```text
Chênh lệch điểm phần trăm = 40 − 50 = −10 điểm phần trăm
Tăng trưởng tương đối    = (40 − 50) / 50 × 100 = −20%
```

**API KPI hiện dùng phép tính đầu tiên.** Vì vậy `−7,14 điểm phần trăm` trong ảnh không có nghĩa là mất 7,14 lịch hoặc giảm tương đối 7,14%.

Nếu kỳ trước bằng 0% nhưng có mẫu số hợp lệ, vẫn trừ bình thường. Ví dụ minh họa 20% − 0% = +20 điểm phần trăm; không chia cho 0. Nếu kỳ trước không có dữ liệu, BE trả `null` thay vì tự đặt kỳ trước bằng 0% hay 100%.

BE hiện trừ hai tỷ lệ đã làm tròn 2 chữ số, không trừ hai con số đã rút gọn còn 1 chữ số trên chart. Do đó cần dùng `change_percentage_points` do API trả; không tính lại từ chuỗi hiển thị `89,3%`.

## 4. Những lịch nào được đưa vào bốn KPI tư vấn?

Gọi **E** là tổng lịch đủ điều kiện đã đến hạn. Một lịch thuộc E khi:

1. Có expert và hồ sơ expert tồn tại tại thời điểm snapshot, khớp bộ lọc.
2. Có ngày hẹn, giờ bắt đầu/kết thúc hợp lệ; bắt đầu trước kết thúc.
3. Ngày hẹn thuộc kỳ đang tính.
4. Giờ kết thúc lịch đã đến tại `snapshot_at`.
5. Trạng thái là `PENDING_CONSULTATION` hoặc `COMPLETED`.
6. Bản ghi yêu cầu không có `created_at` ở tương lai so với snapshot.

Lịch hủy, chưa có expert, chưa có giờ hẹn hoặc chưa tới giờ kết thúc không nằm trong E. Lịch đến hạn nhưng còn `PENDING_CONSULTATION` vẫn thuộc E: chưa hoàn tất sẽ làm giảm các tỷ lệ tương ứng.

BE dùng slot đang liên kết; nếu slot đã giải phóng, dùng metadata lịch đã lưu trên request. Không dùng ngày tạo request để thay cho ngày hẹn của bốn trục này.

Ví dụ minh họa: đang là 10:15, lịch hẹn kết thúc 10:30 thì chưa đưa vào E; lịch kết thúc 10:00 đã đến hạn thì được xét.

## 5. Công thức từng KPI

### 5.1. Chuyên cần — ATTENDANCE

```text
A = số lịch thuộc E đã hoàn thành hợp lệ
    HOẶC có xác nhận expert vào phòng hợp lệ trước/đúng giờ kết thúc
Chuyên cần = A / E × 100
```

Lịch hoàn thành hợp lệ có trạng thái `COMPLETED` và `completed_at <= snapshot_at`. Bằng chứng vào phòng dùng `expert_joined_at`, trong `opens_at..closes_at`, không vượt giờ kết thúc lịch và không ở tương lai. Mỗi lịch chỉ tính một lần dù thỏa cả hai điều kiện.

Lịch cũ hoàn thành vẫn được tính dù không có log video. Chỉ tạo phòng hoặc cấp token không được coi là expert đã vào phòng.

Theo ảnh: **25/28 = 89,29%**.

Chỉ số này đo việc thực hiện lịch tư vấn trên tổng lịch đến hạn. Nó không phải chấm công theo ngày, số giờ online, số ngày nghỉ phép hoặc tỷ lệ nghỉ việc.

### 5.2. Đúng giờ — PUNCTUALITY

```text
P = số lịch thuộc E đã hoàn thành hợp lệ
    VÀ completed_at <= giờ kết thúc đã hẹn
Đúng giờ = P / E × 100
```

Quy tắc hiện tại là **hoàn thành trong giờ hẹn**, không phải chỉ vào phòng đúng giờ bắt đầu. Không có dung sai cộng thêm: `on_time_grace_minutes = 0`.

Ví dụ minh họa, lịch kết thúc 10:00:

| Thời điểm thao tác hoàn thành | Đạt đúng giờ? |
| --- | --- |
| 09:59 | Có |
| 10:00:00 | Có |
| 10:00:01 | Không |
| Đã quá 10:00 nhưng chưa hoàn thành | Không |

Theo ảnh: **12/28 = 42,86%**. Không dùng 12/25 vì mẫu số đã chốt là tổng lịch đến hạn, gồm cả những lịch chưa hoàn tất.

`completed_at` là dấu mốc hoàn thành nghiệp vụ khi expert thao tác kết thúc, không phải phép đo độc lập thời điểm thật sự rời phòng. Đây là giới hạn dữ liệu của chỉ số hiện tại.

### 5.3. Hài lòng — SATISFACTION

```text
R = số đánh giá hợp lệ của các lịch hoàn thành thuộc E
S = số đánh giá trong R có 4 hoặc 5 sao
Hài lòng = S / R × 100
```

Review phải thuộc đúng request/expert, có thời điểm tạo không vượt snapshot. Lịch hoàn thành chưa được đánh giá không vào R, cũng không tự bị chấm không hài lòng.

Theo ảnh: **11/11 = 100%**. Điều này nghĩa là mọi đánh giá được tính đều đạt 4–5 sao; không có nghĩa là mọi khách đã đánh giá hoặc tất cả đều cho 5 sao.

`summary.average_rating` là chỉ số khác: **tổng sao / tổng đánh giá**, trên thang 1–5. Không lấy tỷ lệ hài lòng 100% để suy ra điểm trung bình là 5/5.

### 5.4. Hoàn thành — COMPLETION

```text
C = số lịch thuộc E có trạng thái COMPLETED và completed_at <= snapshot_at
Hoàn thành = C / E × 100
```

Theo ảnh: **25/28 = 89,29%**.

Chuyên cần và hoàn thành có thể khác nhau. Ví dụ minh họa: một lịch có xác nhận expert đã vào phòng nhưng chưa bấm hoàn thành thì có thể đạt chuyên cần, chưa đạt hoàn thành. Lịch hoàn thành trễ vẫn đạt hoàn thành và chuyên cần, nhưng không đạt đúng giờ.

Trong cùng kỳ/phạm vi, số lịch đúng giờ không vượt số lịch hoàn thành; số lịch hoàn thành không vượt số lịch chuyên cần; tất cả không vượt E.

### 5.5. Tiếp nhận đúng hạn — TIMELY_ACCEPTANCE

Chỉ số này được trả khi `includeExtendedMetrics=true`.

```text
Q = yêu cầu RANDOM có dữ liệu thời gian đủ để tính
    VÀ đã được tiếp nhận hoặc đã hết hạn tiếp nhận
T = yêu cầu trong Q được tiếp nhận trước/đúng deadline phục vụ
Tiếp nhận đúng hạn = T / Q × 100
```

**Mốc đã chốt: 30 phút phục vụ trong khung 08:00–20:00 hằng ngày, giờ Việt Nam.** Ngoài giờ dừng đồng hồ; không chạy liên tục 24/24. Hiện chưa có lịch lễ toàn đội riêng.

| Vào hàng chờ | Deadline với SLA 30 phút |
| --- | --- |
| 02:00 | 08:30 cùng ngày |
| 09:00 | 09:30 cùng ngày |
| 19:30 | 20:00 cùng ngày |
| 19:50 | 08:20 hôm sau: 10 phút hôm trước + 20 phút hôm sau |
| 20:00 hoặc 23:00 | 08:30 hôm sau |

Nhận chính xác deadline đạt; trễ một giây không đạt. Nếu expert tự nhận ngoài giờ trước deadline thì vẫn đạt.

Ví dụ minh họa: tại thời điểm xem có 8 yêu cầu đã đủ điều kiện tính, 6 được nhận đúng hạn thì **6/8 = 75%**. Một yêu cầu mới gửi được 5 phút phục vụ và chưa ai nhận không tự làm giảm tỷ lệ vì vẫn trong hạn.

Các quy tắc phạm vi:

- Đặt trực tiếp (`DIRECT`) đang được xác nhận tự động, không có bước expert nhận yêu cầu nên loại khỏi KPI này.
- `RANDOM` ghi `acceptance_opened_at` lúc vào hàng chờ và `accepted_at` lúc nhận. Không dùng `updated_at` để suy ra thời điểm nhận.
- Kỳ của chỉ số này theo **ngày vào hàng chờ**, khác các trục tư vấn theo ngày hẹn. Ví dụ request vào hàng chờ 15/10, hẹn 20/10: tiếp nhận xét kỳ có 15/10, các KPI buổi tư vấn xét kỳ có 20/10 sau khi lịch đến hạn.
- Xem toàn đội: request chưa ai nhận đã hết hạn được đưa vào mẫu số của đội.
- Xem một expert: chỉ tính request đã thuộc người đó; không quy request chưa ai nhận cho mọi expert. Chỉ số cá nhân có phạm vi khác tỷ lệ đội.
- Loại request `CANCELLED`. Lọc chuyên khoa theo hồ sơ expert hiện tại đối với request đã có expert; pool chưa phân công dùng chuyên khoa request.
- Lịch sử thiếu timestamp thật được ghi nhận thiếu dữ liệu, không tự bù số để biến thành đạt/trễ.

`NUTRIMOM_EXPERT_ACCEPTANCE_SLA_MINUTES` cho phép cấu hình 1–1440 phút. Ví dụ trên dùng cấu hình hiện tại 30 phút; SLA đang áp dụng được trả trong `rules`. Đổi cấu hình sẽ tính lại tỷ lệ theo SLA mới khi đọc.

### 5.6. Khách quay lại — RETURNING_CUSTOMERS

Chỉ số này được trả khi `includeExtendedMetrics=true`.

```text
U = số khách khác nhau có ít nhất một buổi hoàn thành thuộc E
K = số khách trong U có một buổi hoàn thành trước đó với cùng expert,
    trước giờ bắt đầu của ít nhất một buổi đang xét
Khách quay lại = K / U × 100
```

Ví dụ minh họa: có 10 khách khác nhau đã hoàn thành tư vấn trong kỳ, 3 người từng hoàn thành tư vấn với đúng expert đó trước buổi quay lại thì **3/10 = 30%**.

- Mỗi người chỉ đếm một lần trong kỳ, dù đến nhiều buổi.
- Đến expert khác không tự là quay lại của expert mới.
- Buổi cũ phải thực sự `COMPLETED`, có `completed_at` hợp lệ; lịch hủy/chưa hoàn tất không phải bằng chứng quay lại.
- Buổi cũ có thể ở trước kỳ lọc hoặc sớm hơn trong cùng kỳ. BE tìm lịch sử ngoài kỳ khi cần.
- Không dùng chính buổi đang xét làm lịch sử; thời điểm hoàn thành buổi cũ phải trước giờ bắt đầu buổi sau và trước thời điểm hoàn thành buổi sau.
- Toàn đội đếm distinct user: một người đến nhiều expert vẫn chỉ tính một khách trong mẫu số; nếu có ít nhất một cặp quay lại hợp lệ thì tính một khách quay lại. Không cộng trực tiếp số khách từng expert vì có thể trùng người.
- Có khách nhưng chưa ai quay lại trả 0%; không có khách có buổi hoàn thành trả `null`.

## 6. Vì sao số toàn đội không lấy trung bình phần trăm từng expert?

Ví dụ minh họa cho một KPI dựa trên lịch:

| Expert | Đạt / tổng | Tỷ lệ |
| --- | --- | --- |
| A | 1/1 | 100% |
| B | 1/9 | 11,11% |

Toàn đội là **(1 + 1) / (1 + 9) × 100 = 20%**, không phải (100 + 11,11) / 2 = 55,56%.

BE tính từ tổng tử/mẫu số theo đơn vị của mỗi KPI. Với khách quay lại, cần đếm lại khách duy nhất ở cấp toàn đội; với tiếp nhận, đội còn gồm pool hết hạn chưa phân công nên không thể chỉ cộng báo cáo cá nhân.

## 7. Phân biệt 0%, thiếu dữ liệu và chưa đủ điều kiện so sánh

| `status` | Ý nghĩa | `percent` |
| --- | --- | --- |
| `AVAILABLE` | Có mẫu số và đủ dữ liệu theo quy tắc KPI | 0–100, gồm 0 thật |
| `NO_ELIGIBLE_CONSULTATIONS` | Không có lịch đủ điều kiện đã đến hạn | `null` |
| `NO_REVIEWS` | Không có đánh giá hợp lệ | `null` |
| `NO_ELIGIBLE_REQUESTS` | Không có yêu cầu đủ điều kiện tính tiếp nhận | `null` |
| `NO_ACCEPTANCE_EVIDENCE` | Có request nhưng thiếu dữ liệu thời gian để tính tiếp nhận | `null` |
| `PARTIAL_DATA` | Tiếp nhận chỉ tính được một phần, còn request thiếu bằng chứng | 0–100 trên phần tính được |
| `NO_COMPLETED_CUSTOMERS` | Không có khách có buổi hoàn thành đủ điều kiện | `null` |

Ví dụ minh họa: 0/5 là 0% thật; 0/0 chưa xác định nên trả `null`, không trả 0% hoặc 100%.

`missing_evidence`:

- Ba trục Chuyên cần/Đúng giờ/Hoàn thành: 0 theo công thức hiện tại; mẫu số là tổng lịch đến hạn.
- Hài lòng: số lịch hoàn thành chưa có đánh giá được tính. Không phải số đánh giá xấu.
- Tiếp nhận đúng hạn: số request trong phạm vi thiếu timestamp để tính chính xác.
- Khách quay lại: 0 theo dữ liệu lịch sử hiện có.

Chỉ tính `change_percentage_points` khi cả `current.status` và `previous.status` đều là `AVAILABLE`. Vì vậy PARTIAL_DATA vẫn có tỷ lệ để xem nhưng chưa có delta so sánh đầy đủ.

## 8. Hợp đồng API và bộ lọc Áp dụng

**Swagger:** `Admin expert performance`.

```http
GET /api/v1/admin/experts/performance
Authorization: Bearer <admin_access_token>
```

Token ADMIN hợp lệ và tài khoản vẫn là ACTIVE ADMIN trong DB mới được đọc. Response dùng envelope của dự án `{data, meta}` và `Cache-Control: no-store`.

| Query | Ý nghĩa |
| --- | --- |
| `fromDate`, `toDate` | Cặp ngày YYYY-MM-DD, tính cả hai đầu, 1–366 ngày, không vượt hôm nay ở Việt Nam. Bỏ cả hai: đầu tháng đến hôm nay. |
| `specialty` | Bỏ để lấy mọi chuyên khoa; giá trị hợp lệ: `OBSTETRICS`, `PSYCHOLOGY`, `HEALTH`. |
| `expertUserId` | UUID expert; bỏ để xem đội. Không tồn tại hoặc không thuộc chuyên khoa đã chọn: 404. |
| `includeExtendedMetrics` | Mặc định `false`: 4 KPI hiện tại; `true`: 6 KPI, thêm tiếp nhận và khách quay lại. |

Ví dụ lấy 6 KPI của toàn đội trong kỳ trên ảnh:

```http
GET /api/v1/admin/experts/performance?fromDate=2026-10-01&toDate=2026-10-15&includeExtendedMetrics=true
```

Ví dụ xem Sức khỏe: thêm `&specialty=HEALTH`. Xem một expert: thêm `&expertUserId=<uuid>`.

Áp dụng ở FE nghĩa là gửi lựa chọn đã chốt trong một request mới hoặc dùng cache hợp lệ cùng phạm vi. Chọn bộ lọc không có dữ liệu thì BE trả các mẫu số 0 và tỷ lệ null tương ứng; không tự chuyển về toàn đội để luôn có hình đẹp. Cùng dữ liệu nằm trong hai kỳ lọc có thể tạo cùng tỷ lệ, nên tỷ lệ không đổi chưa đủ để kết luận nút không hoạt động.

Lỗi: 401/403 khi không có quyền; 400 khi sai kiểu/không parse được; 422 khi phạm vi ngày/query không hợp lệ theo nghiệp vụ; 404 khi expert ngoài phạm vi; 429 khi vượt quota. FE phải kết thúc trạng thái tải và hiển thị lỗi, không tự gọi lặp liên tục.

## 9. Các trường response và cách đối chiếu số

### 9.1. Mỗi trục

| Trường | Kiểu | Cách đọc |
| --- | --- | --- |
| `key`, `label` | string | Mã KPI và tên hiển thị |
| `current.numerator` | số nguyên | Tử số kỳ hiện tại |
| `current.denominator` | số nguyên | Mẫu số kỳ hiện tại, có thể khác giữa các KPI |
| `current.percent` | number hoặc null | Tỷ lệ làm tròn 2 chữ số |
| `current.status` | string | Trạng thái như bảng mục 7 |
| `current.missing_evidence` | số nguyên | Số bản ghi thiếu dữ liệu theo nghĩa riêng của KPI |
| `previous` | cùng cấu trúc current | Tử/mẫu/tỷ lệ của kỳ trước |
| `change_percentage_points` | number hoặc null | Tỷ lệ hiện tại trừ tỷ lệ kỳ trước khi đủ điều kiện |

Ví dụ một trục JSON khớp tử/mẫu và tỷ lệ đã đối chiếu ở snapshot trên:

```json
{
  "key": "ATTENDANCE",
  "label": "Chuyên cần",
  "current": {
    "numerator": 25,
    "denominator": 28,
    "percent": 89.29,
    "status": "AVAILABLE",
    "missing_evidence": 0
  },
  "previous": {
    "numerator": 4,
    "denominator": 4,
    "percent": 100.00,
    "status": "AVAILABLE",
    "missing_evidence": 0
  },
  "change_percentage_points": -10.71
}
```

Thứ tự `axes` hiện tại: `ATTENDANCE`, `PUNCTUALITY`, `SATISFACTION`, `COMPLETION`; khi bật mở rộng thêm `TIMELY_ACCEPTANCE`, `RETURNING_CUSTOMERS`.

### 9.2. Thông tin chung

| Trường | Cách dùng |
| --- | --- |
| `snapshot_at` | Thời điểm BE chốt bộ số liệu; dữ liệu có thể đổi khi lấy snapshot mới |
| `timezone` | `Asia/Ho_Chi_Minh` |
| `period` | `from_date`, `to_date`, `previous_from_date`, `previous_to_date` để hiện rõ hai kỳ |
| `specialty` | Chuyên khoa đã áp dụng, null nếu tất cả |
| `expert` | Expert đã chọn, null nếu xem đội; gồm user_id/full_name/specialty/trạng thái/ngày tạo hồ sơ |
| `rules` | Quy tắc tính thực tế được server trả |
| `realtime_revision` | Chuỗi phiên bản; giữ dạng string, không đổi sang Number |

`rules` hiện gồm:

```json
{
  "on_time_grace_minutes": 0,
  "satisfied_minimum_stars": 4,
  "cohort_basis": "SCHEDULED_END_AT",
  "attendance_evidence": "COMPLETED_OR_VERIFIED_ROOM_JOIN",
  "comparison_basis": "PREVIOUS_EQUAL_LENGTH_PERIOD",
  "acceptance_sla_minutes": 30,
  "acceptance_cohort_basis": "QUEUE_OPENED_AT",
  "acceptance_scope": "RANDOM_QUEUE_TEAM_OR_CLAIMED_EXPERT",
  "returning_customer_basis": "SAME_EXPERT_COMPLETED_BEFORE_VISIT_START",
  "acceptance_opening_time": "08:00",
  "acceptance_closing_time": "20:00",
  "acceptance_timezone": "Asia/Ho_Chi_Minh"
}
```

`acceptance_sla_minutes` có thể khác 30 nếu cấu hình server thay đổi.

`summary`:

| Trường | Ý nghĩa |
| --- | --- |
| `total_experts`, `active_experts` | Số hồ sơ/số expert hoạt động hiện tại trong bộ lọc; không phải số lịch và không phải lịch sử nhân sự tại cuối kỳ trước |
| `eligible_consultations` | E: lịch đủ điều kiện đến hạn của kỳ hiện tại |
| `completed_consultations` | C: lịch hoàn thành hợp lệ |
| `observed_consultations` | Lịch có bằng chứng user hoặc expert tham gia video; không dùng làm mẫu số chuyên cần/đúng giờ hiện tại |
| `rating_count`, `average_rating` | Số đánh giá và điểm trung bình trên thang 1–5; average null nếu không có đánh giá |
| `departures_count`, `departures_status` | Hiện là null / NOT_TRACKED; không lấy profile INACTIVE làm số nghỉ việc |

Active expert cần profile ACTIVE, account ACTIVE và còn role EXPERT. Lịch sử KPI của expert INACTIVE vẫn có thể được tính. `profile_created_at` là ngày tạo hồ sơ, không phải ngày bắt đầu hợp đồng.

## 10. Nguồn dữ liệu và thời điểm tính

| Nguồn | Dùng để tính |
| --- | --- |
| `app.consultation_requests` | User/expert, trạng thái, ngày giờ hẹn dự phòng, completed_at, acceptance_opened_at, accepted_at |
| `app.consultation_slots` | Ngày/giờ hẹn khi còn liên kết slot |
| `app.consultation_video_sessions` | Bằng chứng expert/user vào phòng đã xác minh |
| `app.consultation_reviews` | Rating và thời điểm đánh giá của đúng request/expert |
| `app.expert_profiles`, `app.users`, `app.user_roles` | Phạm vi chuyên khoa/expert và trạng thái đội ngũ hiện tại |

Hai kỳ được đọc ở cùng snapshot nhất quán. Tuy nhiên đây **không phải ảnh chụp lịch sử được đóng băng ở cuối kỳ trước**: lịch thuộc kỳ trước được đánh giá theo trạng thái/timestamps hiện có tại snapshot đang đọc. Ví dụ một review được gửi hôm nay cho buổi thuộc kỳ trước có thể thay đổi tỷ lệ hài lòng kỳ trước khi tải lại. Kỳ phân loại review theo ngày hẹn của lịch đủ điều kiện, không theo ngày gửi review.

Số trên Dashboard có thể còn ở cache hoặc snapshot cũ. Muốn đối chiếu chính xác phải dùng cùng bộ lọc, hai kỳ và snapshot, không so hai lần tải ở hai thời điểm có dữ liệu thay đổi.

## 11. Bàn giao hiển thị và vận hành

- BE chịu trách nhiệm xác định tập dữ liệu, cộng tử/mẫu, tính phần trăm và delta. FE lấy kết quả để định dạng và vẽ, không tải toàn bộ lịch về tự tính từng expert.
- Một request trả cả radar và tổng quan. Chế độ 4 trục dùng 3 truy vấn cố định, 6 trục dùng 4; dùng chung cache/realtime Admin, không thêm request cho mỗi trục.
- Migration V52 cần đi cùng BE có timestamp và hàm deadline mới; không sửa dữ liệu thật để lấp số lịch sử thiếu.
- FE hiện tại vẫn nhận 4 trục. Chỉ bật `includeExtendedMetrics=true` sau khi FE cập nhật mapper, status và bố cục radar 6 hướng.
- Khi thiếu dữ liệu, nhãn ghi —/Chưa có dữ liệu. Nếu vẽ vị trí trục ở tâm, phải phân biệt chấm rỗng thiếu dữ liệu với chấm đặc 0% thật.
- Báo cáo hiệu suất riêng chưa được bổ sung trong lượt BE này. Khi làm phải tái sử dụng cùng công thức và quy tắc phạm vi.

### Cách viết dễ hiểu hơn cho phần so sánh

Đề xuất câu hiển thị, chưa thay giao diện trong lượt viết tài liệu:

> Chuyên cần: 25/28 lịch — 89,29%. Kỳ trước (16–30/09): 100%. Giảm 10,71 điểm phần trăm.
>
> Đúng giờ: 12/28 lịch — 42,86%. Kỳ trước: 50%. Giảm 7,14 điểm phần trăm.
>
> Hài lòng: 11/11 đánh giá — 100%. Bằng kỳ trước, không đổi.

Nếu có thể, hiển thị thêm tử/mẫu của kỳ trước từ API để người đọc biết 100% được tính từ bao nhiêu bản ghi. Không chỉ đưa một dấu +/− thiếu đơn vị so sánh.

## 12. Code dùng làm căn cứ

- `src/main/java/vn/nutrimom/admin/repository/AdminExpertPerformanceRepository.java`: SQL xác định cohort, đếm số và tổng hợp lịch sử.
- `src/main/java/vn/nutrimom/admin/service/AdminExpertPerformanceService.java`: công thức, làm tròn, status và delta.
- `src/main/java/vn/nutrimom/admin/model/AdminQuery.java`: ngày Việt Nam và kỳ trước cùng số ngày.
- `src/main/java/vn/nutrimom/admin/dto/AdminExpertPerformanceDtos.java`: các trường response.
- `src/main/java/vn/nutrimom/admin/web/AdminExpertPerformanceController.java`: endpoint và tham số.
- `src/main/resources/db/migration/V52__expert_acceptance_timing.sql`: timestamp, index và cộng deadline theo giờ phục vụ.

Tài liệu kỹ thuật cùng repository: `docs/admin-expert-performance.md`. Kết quả kiểm thử: `docs/admin-expert-performance-validation.md`.
