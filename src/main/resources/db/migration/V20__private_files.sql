SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

CREATE TABLE app.files (
    id NVARCHAR(36) NOT NULL,
    owner_user_id NVARCHAR(36) NOT NULL,
    pregnancy_id NVARCHAR(36) NULL,
    medical_record_id NVARCHAR(36) NULL,
    purpose NVARCHAR(50) NOT NULL,
    file_name NVARCHAR(255) NOT NULL,
    mime_type NVARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 NVARCHAR(64) NOT NULL,
    storage_key NVARCHAR(500) NOT NULL,
    status NVARCHAR(20) NOT NULL,
    upload_expires_at DATETIMEOFFSET(7) NOT NULL,
    completed_at DATETIMEOFFSET(7) NULL,
    download_token_hash NVARCHAR(64) NULL,
    download_token_expires_at DATETIMEOFFSET(7) NULL,
    version BIGINT NOT NULL CONSTRAINT df_files_version DEFAULT 0,
    created_at DATETIMEOFFSET(7) NOT NULL,
    updated_at DATETIMEOFFSET(7) NOT NULL,
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
GO

CREATE INDEX ix_files_owner_status
    ON app.files(owner_user_id, status, created_at DESC);
GO

CREATE INDEX ix_files_medical_record
    ON app.files(medical_record_id, owner_user_id);
GO
