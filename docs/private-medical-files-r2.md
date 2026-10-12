# Private medical file storage on R2

## Storage and API contract

Files keeps its existing API contract. The browser creates an upload session, PUTs raw bytes
to the returned `/api/v1/files/{id}/content`, completes the upload, and attaches the READY
file ID through `attachment_ids` when saving a medical record. BE writes those bytes to R2
when private-file R2 is enabled and `purpose=MEDICAL_RECORD`; an API-host upload URL does **not** imply local storage.
New medical objects use `storage/{owner_user_id}/{file_id}/{random_id}` inside
`nutrimom-medical-records`. Other Files purposes stay local; public article/avatar storage
keeps its separate existing pipeline. Persisted locations of existing files are not rewritten.

Downloads remain short-lived BE URLs (five minutes), with owner authentication or the
existing signed-token validation. BE reads the private object from R2. No public R2 URL,
storage key, bucket, or credentials are exposed in file responses. Responses disable caches.
This proxy approach does not require the browser to access R2 directly or change R2 CORS.

`FILE_STORAGE_UNAVAILABLE` returns 502 for remote storage failures (503 if an existing R2
file is requested while its adapter is disabled), with `retryable=true`. R2 failures never
fall back to local storage. Missing objects still fail upload completion and never become READY.

## Enable private R2

Create a **separate private bucket**, e.g. `nutrimom-medical-records`. Disable both r2.dev
public access and public custom-domain access. The existing `nutrimom-storage` bucket has
a public r2.dev URL and must not receive medical records.

Configure these values only on BE (never in VITE variables or tracked credentials):

```dotenv
FILES_R2_ENABLED=true
FILES_R2_BUCKET_NAME=nutrimom-medical-records
```

By default this adapter reuses `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, and `R2_SECRET_ACCESS_KEY`.
That credential must have object read/write access to the private bucket. Alternatively set
`FILES_R2_ACCOUNT_ID`, `FILES_R2_ACCESS_KEY_ID`, `FILES_R2_SECRET_ACCESS_KEY` for dedicated access.
The private adapter requires no public URL and does not depend on `R2_ENABLED` for public media.

Enabling it with missing/invalid credentials or the public-media bucket name fails startup.
Bucket privacy itself must be verified in Cloudflare; the S3 adapter cannot prove that r2.dev
and custom-domain access are disabled. Do not enable private-file storage until that is verified.
`FILES_R2_ENABLED=false` explicitly keeps new uploads local for development.

Apply migration `V41__private_file_storage_provider.sql` on restart through Flyway.
Existing file rows are marked LOCAL without changing their keys or bytes. New R2 rows
record provider and bucket; upload, completion and download route using those persisted
values. Changing the default bucket only affects new sessions. Keep read permission for
previous private buckets and the original local directory for legacy files. Existing local
objects are not automatically copied to R2 by this migration.

## Verification

Run `mvn test` and `mvn package`. Tests cover config validation, migration preservation,
local/R2 routing, remote storage errors, bytes/checksums, and the full authenticated medical
attachment API lifecycle with a test S3 transport. That transport is test-only and does not
prove connectivity to Cloudflare.

For a real smoke test after configuration: create a session with a small synthetic PDF or
image, PUT bytes, complete and verify READY, save its ID into a medical record, reload the
record and download the same bytes. Verify the object in the **private** bucket, reject
foreign-user access and expired URLs, and confirm no public URL is available. Use synthetic
data and remove only the smoke test's own objects after verification. Do not mark a real
R2 smoke test as passed unless it was actually run.

The opt-in `PrivateR2LiveStorageTest` exercises the production R2 client and adapter against
the real `nutrimom-medical-records` bucket: it writes a synthetic PNG below
`storage/_smoke-test/{uuid}.png`, checks byte-identical reads, and deletes only that test object.
Run it with `FILES_R2_SMOKE_TEST=true` and the private R2 settings supplied as process
environment variables (the test profile does not import `.env`). This verifies live storage,
not a logged-in browser/FE session. The live test was executed successfully for this setup.

Configure an R2 lifecycle policy for abandoned uploads according to the project's retention
policy. This change does not introduce a file-deletion endpoint or delete legacy objects.

References: [R2 S3 API](https://developers.cloudflare.com/r2/get-started/s3/) and
[public bucket access](https://developers.cloudflare.com/r2/buckets/public-buckets/).
