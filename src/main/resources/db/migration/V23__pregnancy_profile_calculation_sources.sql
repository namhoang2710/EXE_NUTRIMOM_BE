SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

ALTER TABLE app.pregnancies ADD
    conception_date DATE NULL,
    gestational_age_anchor_days INT NULL,
    gestational_age_anchor_date DATE NULL;
GO

ALTER TABLE app.pregnancies ALTER COLUMN calculation_source NVARCHAR(30) NOT NULL;
GO

ALTER TABLE app.pregnancies DROP CONSTRAINT ck_pregnancies_calculation_source;
GO

ALTER TABLE app.pregnancies ADD CONSTRAINT ck_pregnancies_calculation_source
    CHECK (calculation_source IN (
        'LMP', 'EDD', 'LAST_MENSTRUAL_PERIOD', 'CONCEPTION_DATE',
        'ESTIMATED_DUE_DATE', 'ULTRASOUND', 'IVF', 'MANUAL'));
GO

ALTER TABLE app.pregnancy_audits ADD calculation_source NVARCHAR(30) NULL;
GO
