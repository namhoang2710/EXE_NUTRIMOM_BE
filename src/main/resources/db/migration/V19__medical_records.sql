CREATE TABLE app.medical_records (
    id VARCHAR(36) NOT NULL,
    owner_user_id VARCHAR(36) NOT NULL,
    pregnancy_id VARCHAR(36) NOT NULL,
    category VARCHAR(30) NOT NULL,
    title VARCHAR(255) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    facility_name VARCHAR(255) NULL,
    clinician_name VARCHAR(255) NULL,
    summary TEXT NULL,
    note TEXT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_medical_records PRIMARY KEY (id),
    CONSTRAINT fk_medical_records_owner FOREIGN KEY (owner_user_id)
        REFERENCES app.users(id),
    CONSTRAINT fk_medical_records_pregnancy FOREIGN KEY (pregnancy_id)
        REFERENCES app.pregnancies(id),
    CONSTRAINT ck_medical_records_category CHECK (
        category IN ('PRENATAL_VISIT', 'ULTRASOUND', 'LAB_RESULT',
                     'PRESCRIPTION', 'DISCHARGE', 'OTHER'))
);

CREATE INDEX ix_medical_records_owner_occurred
    ON app.medical_records(owner_user_id, occurred_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE app.medical_record_audits (
    id VARCHAR(36) NOT NULL,
    medical_record_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_medical_record_audits PRIMARY KEY (id),
    CONSTRAINT fk_medical_record_audits_record FOREIGN KEY (medical_record_id)
        REFERENCES app.medical_records(id),
    CONSTRAINT fk_medical_record_audits_user FOREIGN KEY (user_id)
        REFERENCES app.users(id)
);

CREATE INDEX ix_medical_record_audits_record_created
    ON app.medical_record_audits(medical_record_id, created_at DESC);

