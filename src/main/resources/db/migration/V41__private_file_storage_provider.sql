-- Existing objects remain on LOCAL storage. Enabling R2 only affects new upload sessions.
ALTER TABLE app.files
    ADD COLUMN storage_provider VARCHAR(10) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE app.files
    ADD COLUMN storage_bucket VARCHAR(63) NULL;

ALTER TABLE app.files ADD CONSTRAINT ck_files_storage_provider
    CHECK (storage_provider IN ('LOCAL', 'R2'));

ALTER TABLE app.files ADD CONSTRAINT ck_files_storage_bucket
    CHECK ((storage_provider = 'LOCAL' AND storage_bucket IS NULL)
        OR (storage_provider = 'R2' AND storage_bucket IS NOT NULL AND LENGTH(storage_bucket) >= 3));
