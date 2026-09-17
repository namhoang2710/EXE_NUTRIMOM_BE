# Knowledge CMS API

The module uses the repository's Spring Boot 4.1 / Java 17, JWT security,
`ApiResponse` (`data`, `meta`) and structured `ApiErrorResponse` (`error`).
The new article DTOs explicitly use camelCase for `publishedAt`, `coverImage`,
`youtubeVideoId`, `createdAt`, `updatedAt`, `sortOrder` and pagination fields. Existing APIs,
metadata (`request_id`, `server_time`) and media upload DTOs remain snake_case.
Article requests also accept the snake_case aliases of their camelCase fields.

## Endpoints

| Method | Path | Access |
|---|---|---|
| POST | `/api/v1/admin/knowledge/articles` | ADMIN, create, 201 |
| PUT | `/api/v1/admin/knowledge/articles/{id}` | ADMIN, full replacement |
| DELETE | `/api/v1/admin/knowledge/articles/{id}` | ADMIN |
| GET | `/api/v1/admin/knowledge/articles` | ADMIN, includes drafts/archived/scheduled |
| GET | `/api/v1/admin/knowledge/articles/{id}` | ADMIN |
| POST | `/api/v1/admin/knowledge/media/upload` | ADMIN, multipart, 201 |
| GET | `/api/v1/knowledge/articles` | Public; savedOnly requires JWT |
| GET | `/api/v1/knowledge/articles/{slug}` | Public |
| PUT | `/api/v1/knowledge/articles/{slug}/bookmark` | Authenticated, idempotent |
| DELETE | `/api/v1/knowledge/articles/{slug}/bookmark` | Authenticated, idempotent |

Public endpoints return only `published` articles whose `publishedAt` is not in
the future. Missing, draft, archived and future articles return the same 404.
No database or R2 credentials are needed just to construct the disabled storage
service; uploading while R2 is disabled returns `R2_UPLOAD_FAILED` / 503.

## Article creation / replacement

First upload any images, then reference the returned media `id` or exact
`image_url` in `coverImage` and section `image`. Arbitrary image URLs are rejected;
if both id and URL are supplied they must identify the same uploaded image.
`alt` and `caption` can override the upload metadata for an individual placement.
`authorId` defaults to the authenticated admin when creating, and retains the
existing author when omitted on replacement. An explicit author must exist.

```json
{
  "slug": "vitamin-during-pregnancy",
  "title": "Vitamin D khi mang thai",
  "excerpt": "Tổng quan về vitamin D trong thai kỳ",
  "category": "Dinh dưỡng",
  "stage": "Trong thai kỳ",
  "topics": ["Vitamin", "Chăm sóc hằng ngày"],
  "status": "published",
  "publishedAt": "2026-09-01T00:00:00Z",
  "coverImage": { "id": "<uploaded-media-id>", "alt": "Vitamin D" },
  "lead": "Nội dung mở đầu",
  "youtubeVideoId": "dQw4w9WgXcQ",
  "sections": [
    {
      "heading": "Tại sao quan trọng?",
      "paragraphs": ["Nội dung đoạn văn"],
      "bullets": ["Ý chính"],
      "image": { "id": "<uploaded-media-id>", "caption": "Chú thích" }
    }
  ],
  "source": { "label": "WHO", "href": "https://www.who.int" }
}
```

`status` is one of `draft`, `published`, `archived`. Publishing without an
explicit timestamp preserves the previous publication timestamp or uses UTC
now. Other statuses clear `publishedAt`. Section array order defines `sortOrder`;
PUT replaces the sections, assigns new section IDs, and removes the previous
section records. An omitted cover/source/sections clears that content on PUT.
`youtubeVideoId` is an optional article property, separate from `sections`, for
one YouTube video displayed after all sections. The request accepts its
`youtube_video_id` alias. Only an ID of exactly 11 characters from
`[A-Za-z0-9_-]` is accepted; empty strings, whitespace, full URLs and HTML
return `VALIDATION_ERROR` / 422. The backend stores only the ID and does not
check YouTube availability. On PUT, omitting `youtubeVideoId` or sending `null`
removes the video. The response omits the field when no video is attached.
`createdAt` and `updatedAt` are managed by JPA; optimistic locking prevents
silent concurrent article overwrites. Slugs are lowercase letters/digits with
single hyphen separators, at most 180 characters, and database-unique.

Supported categories:

- FE labels: `Dinh dưỡng`, `Sống khỏe`, `Vận động`, `Thai kỳ`, `Sau sinh`, `Chuẩn bị`.
- API identifiers: `nutrition`, `wellness`, `exercise`, `pregnancy`, `postpartum`, `preconception`.

Supported stages:

- FE labels: `Chuẩn bị mang thai`, `Trong thai kỳ`, `Sau sinh`.
- API identifiers: `preconception`, `pregnancy`, `trimester-1`, `trimester-2`, `trimester-3`, `postpartum`.

Values are stored as supplied and filters match those values. Choose one
vocabulary consistently in the admin UI; identifiers and labels are not aliases
in the database. Topics are distinct, validated strings (up to 30 per article).
Paragraph and bullet arrays use JSON in NVARCHAR(MAX), with no HTML processing.
Render their content as text in the FE.

## Lists and pagination

`page` is one-based (default 1), `pageSize` defaults to 12 and is limited to
1..100. Optional `category`, `stage` and `topic` filters combine with AND; topic
matches the complete, case-sensitive topic string. `savedOnly=true` uses the current JWT account's
bookmarks. Anonymous saved-only requests return 401.
Admin lists additionally accept `status` and return the existing detail-like
items without `youtubeVideoId`; public lists return summary objects. Sort is
`field:asc` or `field:desc`; allowed fields are
`publishedAt`, `title`, `createdAt`, `updatedAt`. Defaults are `publishedAt:desc`
(public), `updatedAt:desc` (admin), with ID as a stable tie-breaker.

```json
{
  "data": {
    "items": [],
    "totalItems": 0,
    "totalPages": 0,
    "currentPage": 1,
    "pageSize": 12
  },
  "meta": { "request_id": "...", "server_time": "..." }
}
```

Pages above the last page clamp to the last page, as in the current FE.
Summary fields: `id`, `slug`, `title`, `excerpt`, `category`, `stage`, `topics`,
`publishedAt`, `author: {id,name}`, `coverImage: {url,alt,caption}` (when present).
Detail adds `lead`, `sections`, `source`, `status`, `youtubeVideoId`, `createdAt`, `updatedAt`.
Each section has `id`, `heading`, `paragraphs`, `bullets`, `sortOrder`, optional
`image: {url,alt,caption}`. Null optional fields are omitted as in existing APIs.

## FE integration

The current sibling FE has fixtures in
`src/features/knowledge/model/article-content.ts`, an async mock pagination
boundary in `library-pagination.ts`, and device-local bookmarks in
`use-article-bookmarks.ts`. Its own comments require an API-to-view-model adapter.
This backend implements the requested CMS contract; the FE files are unchanged.

- Map `response.data` to the pagination view model; send `pageSize=6` to retain
  the current library page size.
- For the existing card/detail view model, format ISO `publishedAt` as
  `dd.MM.yyyy`, map `author.name` to `editorial.author`, and calculate the
  display `readTime` from text length. Use the FE label vocabulary to retain
  its present stage/category filters, or translate API identifiers in the adapter.
- Reviewer confirmations and featured selection are not part of this CMS model.
  Do not fabricate clinical reviewer metadata from `status`; keep `reviewer=null`
  and `selected=false` until an actual review/selection workflow is implemented.
  Published content can map to the UI's publication moderation flag.
- Replace the bookmark toggle's local-only persistence with PUT/DELETE and use
  `savedOnly=true` with JWT to load saved articles. Local fixture bookmarks do not
  automatically become server bookmarks. Existing local slugs can be migrated
  by authenticated PUT requests after their articles exist in the CMS.
- Use `coverImage.url` and `sections[].image.url` when adding image rendering;
  the current `BlogSection` fixture type has no image field.
- Render the optional video after the detail `sections`. Construct the iframe
  URL from the validated ID, for example
  `https://www.youtube-nocookie.com/embed/{id}`; never render supplied HTML or
  accept an embed URL in place of the ID.

## R2 configuration and upload policy

Set these environment variables (also listed in `.env.example`):

```dotenv
R2_ENABLED=true
R2_ACCOUNT_ID=<32-character-cloudflare-account-id>
R2_ACCESS_KEY_ID=<r2-s3-access-key>
R2_SECRET_ACCESS_KEY=<r2-s3-secret>
R2_BUCKET_NAME=<bucket-name>
R2_PUBLIC_BASE_URL=https://media.example.com
```

Use a public R2 custom domain for `R2_PUBLIC_BASE_URL`; the S3 API endpoint is
for authenticated storage operations, not the image URL served to browsers.
R2 configuration is validated at startup when enabled. See
[Cloudflare's Java SDK configuration](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/)
and [public bucket/custom-domain setup](https://developers.cloudflare.com/r2/buckets/public-buckets/).
The SDK uses region `auto`, path-style access, disabled chunked encoding,
required-only request checksums, bounded network timeouts and SDK retries.

Send `multipart/form-data` directly to the Spring backend with required `file`
and optional `alt` (500 characters), `caption` (1000 characters). Do not forward
the binary payload through a Vercel function.

- Accept JPEG (`image/jpeg` or `image/jpg`), PNG, WebP; decoder format must match MIME.
- Maximum input: 10 MiB; maximum multipart request: 11 MiB. The memory threshold
  equals the request limit so accepted uploads do not spill onto disk.
- Read header dimensions before decoding; reject dimensions above 12000 pixels
  or 40 million pixels. Use decoder subsampling before allocating full pixels.
- At most two concurrent image processing jobs per process; excess jobs return 429.
- Resize preserving aspect ratio without upscaling, max 2400 pixels each side.
- Re-encode as JPEG, strip source metadata, flatten transparency onto white;
  animated input uses the first frame. Try reduced quality and, if needed,
  smaller dimensions until the output is at most 2 MiB.
- Object keys: `article/{UTC-year}/{UTC-month}/{uuid}.jpg`; content-type and cache
  headers reflect the optimized object. Images and request bytes stay in memory.
- SQL contains only URL/key, dimensions, size, alt/caption, content type,
  uploader and timestamps, plus references from article/section records.

Upload response:

```json
{
  "data": {
    "id": "<uuid>",
    "image_url": "https://media.example.com/article/2026/09/<uuid>.jpg",
    "image_key": "article/2026/09/<uuid>.jpg",
    "alt": "Image description",
    "caption": "Caption",
    "width": 1600,
    "height": 900,
    "size_bytes": 200000
  },
  "meta": { "request_id": "...", "server_time": "..." }
}
```

R2 upload precedes metadata insertion. Metadata is committed in its own SQL
transaction; failures including commit failures attempt deletion of the uploaded
object. Failed/ambiguous R2 uploads also attempt deletion with the generated key
and never insert metadata. Logs include MDC request ID and object key. If R2
deletion itself fails after SDK retries, `R2_ORPHAN_CLEANUP_FAILED` logs the
bucket/key for operational reconciliation; an object store outage cannot be
made atomic with a SQL transaction. Alert on this event and retry deletion after
R2 recovers. Upload metadata is independent of articles; deleting or replacing
an article retains uploaded media so shared images remain valid. Unused media
retention/deletion is an administrative maintenance policy.

## Errors and migrations

Errors use the existing `error.code/message/fields/retryable/request_id` shape.
Key codes: `INVALID_FILE_TYPE`, `FILE_TOO_LARGE`, `INVALID_IMAGE_DIMENSIONS`,
`IMAGE_PROCESSING_FAILED`, `IMAGE_PROCESSING_BUSY`, `INVALID_MULTIPART`,
`R2_UPLOAD_FAILED`, `DB_SAVE_FAILED`, `SLUG_ALREADY_EXISTS`, `ARTICLE_NOT_FOUND`,
`VALIDATION_ERROR`, `VERSION_CONFLICT` (409 for concurrent article updates; reload
the article before retrying). Existing JWT failures retain `UNAUTHORIZED` / `FORBIDDEN`.

Flyway V9..V12 create media, articles/topics, sections and bookmarks in `app`,
with FKs, unique slug/object key/bookmark indexes, publication/status checks,
JSON section checks and filter indexes. Existing identity V1 and user profile/onboarding V2 migrations are unchanged.
V13 adds the nullable cover caption column and fills existing articles from their
uploaded media caption when available. Previously discarded custom cover captions
cannot be recovered; submit them again in an article replacement request.
V14 adds the nullable `youtube_video_id NVARCHAR(11)` article column. Existing
articles have no video until an admin supplies an ID.

Media must precede articles and sections because both reference the media table.
Local databases that already applied an older Knowledge migration sequence require
migration-history reconciliation before using V9..V14. Renaming files alone does
not update an existing database; reset disposable development databases or reconcile
the Flyway history and existing Knowledge tables before starting the application.

Run `./mvnw.cmd clean verify` with JDK 17. H2 tests exercise actual article,
bookmark, image optimization and SQL persistence behavior, mocking only the
external R2 SDK. A real embedded-server HTTP test uses a signed JWT and checks
both a successful multipart upload and servlet rejection of an oversized file.
Tests also cover DB rollback when R2 cleanup itself fails. H2 uses generated JPA schema; SQL Server Flyway migrations and
live R2 require verification against their respective deployment services.
