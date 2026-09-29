SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

CREATE TABLE app.pregnancies (
    id NVARCHAR(36) NOT NULL,
    owner_user_id NVARCHAR(36) NOT NULL,
    status NVARCHAR(20) NOT NULL,
    estimated_due_date DATE NOT NULL,
    last_menstrual_period DATE NOT NULL,
    calculation_source NVARCHAR(20) NOT NULL,
    care_facility_name NVARCHAR(255) NULL,
    care_provider_name NVARCHAR(255) NULL,
    version BIGINT NOT NULL CONSTRAINT df_pregnancies_version DEFAULT 0,
    created_at DATETIMEOFFSET(7) NOT NULL,
    updated_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_pregnancies PRIMARY KEY (id),
    CONSTRAINT fk_pregnancies_owner FOREIGN KEY (owner_user_id)
        REFERENCES app.users(id),
    CONSTRAINT ck_pregnancies_status CHECK (
        status IN ('ACTIVE', 'COMPLETED', 'LOSS_REPORTED', 'ARCHIVED')),
    CONSTRAINT ck_pregnancies_calculation_source CHECK (
        calculation_source IN ('LMP', 'EDD', 'ULTRASOUND', 'IVF', 'MANUAL'))
);
GO

CREATE UNIQUE INDEX ux_pregnancies_owner_active
    ON app.pregnancies(owner_user_id)
    WHERE status = 'ACTIVE';
GO

CREATE INDEX ix_pregnancies_owner_created
    ON app.pregnancies(owner_user_id, created_at DESC);
GO
