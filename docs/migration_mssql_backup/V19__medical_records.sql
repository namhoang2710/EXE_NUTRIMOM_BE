SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

CREATE TABLE app.medical_records (
    id NVARCHAR(36) NOT NULL,
    owner_user_id NVARCHAR(36) NOT NULL,
    pregnancy_id NVARCHAR(36) NOT NULL,
    category NVARCHAR(30) NOT NULL,
    title NVARCHAR(255) NOT NULL,
    occurred_at DATETIMEOFFSET(7) NOT NULL,
    facility_name NVARCHAR(255) NULL,
    clinician_name NVARCHAR(255) NULL,
    summary NVARCHAR(MAX) NULL,
    note NVARCHAR(MAX) NULL,
    deleted_at DATETIMEOFFSET(7) NULL,
    version BIGINT NOT NULL CONSTRAINT df_medical_records_version DEFAULT 0,
    created_at DATETIMEOFFSET(7) NOT NULL,
    updated_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_medical_records PRIMARY KEY (id),
    CONSTRAINT fk_medical_records_owner FOREIGN KEY (owner_user_id)
        REFERENCES app.users(id),
    CONSTRAINT fk_medical_records_pregnancy FOREIGN KEY (pregnancy_id)
        REFERENCES app.pregnancies(id),
    CONSTRAINT ck_medical_records_category CHECK (
        category IN ('PRENATAL_VISIT', 'ULTRASOUND', 'LAB_RESULT',
                     'PRESCRIPTION', 'DISCHARGE', 'OTHER'))
);
GO

CREATE INDEX ix_medical_records_owner_occurred
    ON app.medical_records(owner_user_id, occurred_at DESC, id DESC)
    WHERE deleted_at IS NULL;
GO

CREATE TABLE app.medical_record_audits (
    id NVARCHAR(36) NOT NULL,
    medical_record_id NVARCHAR(36) NOT NULL,
    user_id NVARCHAR(36) NOT NULL,
    event_type NVARCHAR(40) NOT NULL,
    created_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_medical_record_audits PRIMARY KEY (id),
    CONSTRAINT fk_medical_record_audits_record FOREIGN KEY (medical_record_id)
        REFERENCES app.medical_records(id),
    CONSTRAINT fk_medical_record_audits_user FOREIGN KEY (user_id)
        REFERENCES app.users(id)
);
GO

CREATE INDEX ix_medical_record_audits_record_created
    ON app.medical_record_audits(medical_record_id, created_at DESC);
GO
