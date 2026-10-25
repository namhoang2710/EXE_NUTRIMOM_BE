# API đánh giá web

Nhánh `feature/web-reviews` · 25/10/2026 · Migration `V69__web_reviews.sql` (**phải merge sau V68** của
`feature/forgot-password-email`; Flyway tắt out-of-order).

JSON snake_case, bọc `ApiResponse`. Trường null bị **bỏ khỏi JSON** (vd. chưa có `reply`). Danh sách dùng
`CursorPage` (`items`, `next_cursor`, `has_more`), cursor keyset mới trước. `limit` 1–50, mặc định 10.

## Quy tắc
- Viết đánh giá: đã đăng nhập, tài khoản ACTIVE, có vai trò USER. Admin, chuyên gia, biên tập viên → 403 `WEB_REVIEW_NOT_ALLOWED`.
- **Mỗi người 1 đánh giá** còn hiệu lực: `PUT /reviews/me` lần đầu tạo (201), các lần sau là sửa (200, bắt buộc `version`).
- `rating` 1–5. `content` 10–1000 ký tự (đếm theo ký tự, emoji tính 1), làm sạch ký tự vô hình. Trả về dạng **chữ thuần**: FE hiển thị bằng text, không render HTML.
- Hiện công khai **ngay**. Admin ẩn được (kèm lý do). Đang bị ẩn thì người viết không sửa được (409 `WEB_REVIEW_HIDDEN`), nhưng vẫn xoá được.
- Trang công khai chỉ hiện **tên đã che** (`Nguyễn Thị Lan` → `Lan N.`, `Lan` → `L***`). Không có id, avatar, email hay sđt người viết. Tài khoản bị khoá thì đánh giá tự rút khỏi trang công khai.
- Người viết sửa đánh giá sau khi đã có trả lời: giữ trả lời, bật `edited_after_reply` để admin xem lại.

## Công khai (không cần đăng nhập)
| Method | Path | Ghi chú |
|---|---|---|
| GET | `/api/v1/reviews?rating=&cursor=&limit=` | Item: `id, rating, content, author{display_name}, created_at, edited_at?, reply{content, replied_at, updated_at?}?` |
| GET | `/api/v1/reviews/summary` | `{ average?, total, by_rating: {"5": n, "4": n, "3": n, "2": n, "1": n} }`. `average` (1 chữ số thập phân) không có khi `total = 0` |

## Người dùng (đăng nhập)
| Method | Path | Body | Kết quả |
|---|---|---|---|
| GET | `/api/v1/reviews/me` | — | `id, rating, content, status (PUBLISHED/HIDDEN), hidden_reason?, reply?, version, created_at, edited_at?`. Chưa có → 404 `WEB_REVIEW_NOT_FOUND` |
| PUT | `/api/v1/reviews/me` | `{ rating, content, version? }` | 201 (tạo) / 200 (sửa). Sai/thiếu `version` khi sửa → 409 `VERSION_CONFLICT` / 422 |
| DELETE | `/api/v1/reviews/me` | — | 204. Xoá mềm, sau đó viết lại được |

## Admin (`hasRole('ADMIN')`)
| Method | Path | Body | Ghi chú |
|---|---|---|---|
| GET | `/api/v1/admin/reviews?status=&rating=&has_reply=&q=&cursor=&limit=` | — | Mọi trạng thái (PUBLISHED/HIDDEN/DELETED). `q` tìm trong nội dung và tên người viết. Item thêm `author{id, display_name, email_masked, phone_masked, account_status}`, `replied_by`, `edited_after_reply`, `hidden_at`, `deleted_at`, `version` |
| GET | `/api/v1/admin/reviews/pending-count` | — | `{ count }`: đang công khai mà chưa trả lời |
| GET | `/api/v1/admin/reviews/{id}` | — | Chi tiết |
| PUT | `/api/v1/admin/reviews/{id}/reply` | `{ content, version }` | Tạo/sửa trả lời (2–1000 ký tự). **Lần đầu** gửi thông báo cho người viết. Xoá cờ `edited_after_reply` |
| DELETE | `/api/v1/admin/reviews/{id}/reply?version=` | — | Gỡ trả lời |
| POST | `/api/v1/admin/reviews/{id}/hide` | `{ reason, version }` | Ẩn + gửi thông báo cho người viết |
| POST | `/api/v1/admin/reviews/{id}/unhide` | `{ version }` | Hiện lại |

Đánh giá đã bị xoá → 409 `WEB_REVIEW_DELETED` cho reply/hide/unhide. Mỗi thao tác admin làm tăng `version`.

## Thông báo
- Đánh giá mới → sự kiện admin `ADMIN_REVIEW_NEW` (hộp thông báo admin, nhóm SUPPORT, link `nutrimom://admin/reviews/{id}`).
- Admin trả lời lần đầu / ẩn đánh giá → thông báo trong app cho người viết (type `SYSTEM`, link `nutrimom://reviews/me`). Nội dung không nhắc lại đánh giá.

## Rate limit
| Route | Policy | Ngưỡng |
|---|---|---|
| `/reviews`, `/reviews/summary` | `reviews-public` (theo IP) | 60 / phút |
| `/reviews/me` | `reviews-me` (theo người dùng) | 30 / 15 phút |
| `/admin/reviews/**` | `default` | 100 / phút |
