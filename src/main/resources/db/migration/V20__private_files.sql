CREATE TABLE app.files (
    id VARCHAR(36) NOT NULL,
    owner_user_id VARCHAR(36) NOT NULL,
    pregnancy_id VARCHAR(36) NULL,
    medical_record_id VARCHAR(36) NULL,
    purpose VARCHAR(50) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    upload_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    download_token_hash VARCHAR(64) NULL,
    download_token_expires_at TIMESTAMP WITH TIME ZONE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_files PRIMARY KEY (id),
    CONSTRAINT fk_files_owner FOREIGN KEY (owner_user_id)
        REFERENCES app.users(id),
    CONSTRAINT fk_files_pregnancy FOREIGN KEY (pregnancy_id)
        REFERENCES app.pregnancies(id),
    CONSTRAINT fk_files_medical_record FOREIGN KEY (medical_record_id)
        REFERENCES app.medical_records(id),
    CONSTRAINT ck_files_status CHECK (
        status IN ('UPLOADING', 'READY', 'QUARANTINED', 'FAILED', 'EXPIRED'))
);

CREATE INDEX ix_files_owner_status
    ON app.files(owner_user_id, status, created_at DESC);

CREATE INDEX ix_files_medical_record
    ON app.files(medical_record_id, owner_user_id);

