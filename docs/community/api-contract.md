# Cộng đồng dành cho mẹ — hợp đồng API

Mọi endpoint **bắt buộc đăng nhập** (Bearer JWT). Response bọc `ApiResponse` `{ data, meta }`, JSON snake_case.
Lỗi: `{ error: { code, message, fields, retryable, request_id } }` theo `ErrorCode` (xem `docs/error-codes.md`).

Phân trang dạng cursor: `{ items, next_cursor, has_more }`. Gửi lại `next_cursor` nguyên văn ở `?cursor=`;
không gửi cursor = trang đầu. `next_cursor` là chuỗi opaque, FE không tự tạo.

Migration: `V65__community.sql`.

## Giá trị enum

| Enum | Giá trị |
|---|---|
| `topic` | `PREGNANCY`, `NUTRITION`, `MOTHER_HEALTH`, `BABY_CARE`, `SHARING`, `OTHER` |
| `status` (bài) | `PENDING` (chờ duyệt), `APPROVED`, `REJECTED`, `REMOVED` (admin gỡ) |
| `reason` (báo cáo) | `SPAM`, `OFFENSIVE`, `MISINFORMATION`, `HARASSMENT`, `OTHER` |
| `type` (thông báo) | `POST_LIKED`, `POST_COMMENTED`, `POST_APPROVED`, `POST_REJECTED`, `POST_REMOVED` |

## Đối tượng

`PostItem`
```json
{
  "id": "uuid", "author": { "id": "uuid", "display_name": "Lan", "avatar_key": null },
  "content": "văn bản thuần", "topic": "NUTRITION",
  "images": [{ "id": "uuid", "url": "https://pub-….r2.dev/community/…jpg", "width": 1600, "height": 1200 }],
  "like_count": 3, "comment_count": 1, "liked_by_me": true, "saved_by_me": false, "pinned": false,
  "status": "APPROVED", "reject_reason": "chỉ có khi là bài của mình",
  "can_edit": false, "can_delete": false, "version": 2,
  "published_at": "…", "created_at": "…", "edited_at": null
}
```
`content` là **văn bản thuần** — FE phải hiển thị bằng text (không `dangerouslySetInnerHTML`).
`avatar_key` có thể null. `can_edit`/`can_delete` chỉ là gợi ý hiển thị; BE vẫn kiểm quyền.

## Người dùng — `/api/v1/community`

| Method | Path | Body / query | Trả về |
|---|---|---|---|
| GET | `/posts` | `cursor`, `limit` (1–20, mặc định 10), `topic`, `has_images`, `saved` | `{ items: PostItem[], pinned?: PostItem[], next_cursor, has_more }` — `pinned` (≤3) **chỉ có ở trang đầu**, không lặp trong `items` |
| GET | `/posts/{id}` | — | `PostItem` (bài đã duyệt, hoặc bài của mình ở mọi trạng thái); khác → 404 |
| POST | `/posts` | `{ content (1–5000), topic, image_ids?: string[≤4] }` | 201 `PostItem` (`status: PENDING`) |
| PATCH | `/posts/{id}` | `{ content, topic, image_ids, version }` | `PostItem`; bài đã duyệt quay về `PENDING`; sai `version` → 409 `VERSION_CONFLICT` |
| DELETE | `/posts/{id}` | — | `true` (chủ bài, xoá mềm) |
| GET | `/me/posts` | `cursor`, `limit`, `status` | trang `PostItem` của mình, có `reject_reason` |
| POST | `/media` | multipart `file` (JPEG/PNG/WebP ≤10 MiB) | 201 `{ id, url, width, height }` — gửi `id` vào `image_ids` trong 24h |
| PUT / DELETE | `/posts/{id}/like` | — | `{ liked, like_count }` (idempotent) |
| PUT / DELETE | `/posts/{id}/save` | — | `{ saved }` (idempotent) |
| GET | `/posts/{id}/comments` | `cursor`, `limit` (1–50, mặc định 20) | trang `{ id, post_id, author, content, can_delete, created_at }`, cũ → mới |
| POST | `/posts/{id}/comments` | `{ content (1–1000) }` | 201 comment |
| DELETE | `/comments/{id}` | — | `true` (chỉ người viết; khác → 404) |
| POST | `/posts/{id}/reports` | `{ reason, note? (≤500) }` | 201 `{ created: true }` lần đầu, 200 `{ created: false }` nếu đã báo cáo |

### Thông báo cộng đồng — `/api/v1/community/notifications` (tách khỏi chuông header)
| Method | Path | Trả về |
|---|---|---|
| GET | `?cursor&limit(1–50)&unread_only` | trang `{ id, type, post_id, comment_id?, actor?, message, read, created_at }` |
| GET | `/unread-count` | `{ unread_count }` — gọi khi mở trang/focus, **không polling** |
| POST | `/{id}/read` | `true` (idempotent; của người khác → 404) |
| POST | `/read-all` | `{ updated }` |

Thông báo kiểm duyệt (`POST_APPROVED/REJECTED/REMOVED`) không có `actor`. Không có thông báo "bị báo cáo";
không bao giờ lộ người báo cáo.

## Đã lưu chung — `/api/v1/me/saved-items`
`GET ?type=ALL|KNOWLEDGE|COMMUNITY&cursor&limit(1–50, mặc định 20)` →
trang `{ type, id, slug?, title?, excerpt, thumbnail_url?, saved_at }`, mới lưu trước.
Điều hướng: `KNOWLEDGE` → `/app/knowledge/{slug}`; `COMMUNITY` → trang cộng đồng với bài `id`
(lấy chi tiết bằng `GET /community/posts/{id}`). API bookmark Knowledge cũ không đổi.

## Admin — `/api/v1/admin/community` (role ADMIN)
| Method | Path | Body | Trả về |
|---|---|---|---|
| GET | `/posts?status&cursor&limit` | — | trang `{ post: PostItem, open_report_count, moderated_by, moderated_at, deleted_at }` |
| GET | `/posts/{id}` | — | như trên |
| POST | `/posts/{id}/approve` | — | |
| POST | `/posts/{id}/reject` | `{ reason }` | |
| PUT / DELETE | `/posts/{id}/pin` | — | tối đa 3 bài ghim → 409 `COMMUNITY_PIN_LIMIT_REACHED` |
| DELETE | `/posts/{id}` | `{ reason? }` | gỡ bài (REMOVED), đóng mọi báo cáo OPEN, báo chủ bài |
| DELETE | `/comments/{id}` | — | xoá bình luận bất kỳ |
| GET | `/reports?status=OPEN\|DISMISSED\|ACTION_TAKEN&cursor&limit` | — | trang báo cáo (có `reporter`, `post_excerpt`, `open_report_count`) |
| POST | `/reports/{id}/resolve` | `{ action: DISMISS\|REMOVE_POST, note? }` | báo cáo sau xử lý |

## Lỗi hay gặp
| HTTP | code | Khi nào | FE nên |
|---|---|---|---|
| 401 | `UNAUTHORIZED` | chưa đăng nhập / token hết hạn | về trang đăng nhập kèm redirect |
| 403 | `FORBIDDEN` | tài khoản bị khoá; không phải admin | báo không có quyền |
| 404 | `RESOURCE_NOT_FOUND` | bài/bình luận không tồn tại hoặc không được xem | bỏ khỏi danh sách |
| 409 | `VERSION_CONFLICT` | sửa bài với version cũ | tải lại bài |
| 422 | `VALIDATION_ERROR`, `COMMUNITY_MEDIA_INVALID`, `INVALID_FILE_TYPE`, `IMAGE_PROCESSING_FAILED` | dữ liệu sai, ảnh hết hạn/không thuộc mình | hiện `error.fields`/`message` |
| 413 | `FILE_TOO_LARGE` | ảnh > 10 MiB | |
| 429 | `RATE_LIMITED`, `QUOTA_EXCEEDED`, `IMAGE_PROCESSING_BUSY` | thao tác quá nhanh / hết quota 24h | đọc `Retry-After`, **không tự retry dồn dập** |
| 502 | `R2_UPLOAD_FAILED` | R2 lỗi/timeout hoặc chưa cấu hình | cho bấm thử lại ảnh đó |

## Giới hạn
- Rate limit (mỗi người dùng): upload 10/5 phút, báo cáo 5/10 phút, tim + lưu 60/phút, bình luận (đọc + ghi) 30/phút,
  còn lại `/community/**` 100/phút.
- Quota 24 giờ: 10 bài, 100 bình luận, 40 ảnh. Tối đa 4 ảnh/bài, 3 bài ghim.
- Ảnh upload mà không đăng sẽ bị xoá sau 24 giờ; ảnh của bài bị xoá/gỡ bị xoá khỏi R2 sau 7 ngày.
