CREATE TABLE app.pregnancies (
    id VARCHAR(36) NOT NULL,
    owner_user_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    estimated_due_date DATE NOT NULL,
    last_menstrual_period DATE NOT NULL,
    calculation_source VARCHAR(20) NOT NULL,
    care_facility_name VARCHAR(255) NULL,
    care_provider_name VARCHAR(255) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_pregnancies PRIMARY KEY (id),
    CONSTRAINT fk_pregnancies_owner FOREIGN KEY (owner_user_id)
        REFERENCES app.users(id),
    CONSTRAINT ck_pregnancies_status CHECK (
        status IN ('ACTIVE', 'COMPLETED', 'LOSS_REPORTED', 'ARCHIVED')),
    CONSTRAINT ck_pregnancies_calculation_source CHECK (
        calculation_source IN ('LMP', 'EDD', 'ULTRASOUND', 'IVF', 'MANUAL'))
);

CREATE UNIQUE INDEX ux_pregnancies_owner_active
    ON app.pregnancies(owner_user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_pregnancies_owner_created
    ON app.pregnancies(owner_user_id, created_at DESC);

