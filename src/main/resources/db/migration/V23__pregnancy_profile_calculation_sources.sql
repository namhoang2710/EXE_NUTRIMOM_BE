ALTER TABLE app.pregnancies
    ADD COLUMN conception_date DATE NULL,
    ADD COLUMN gestational_age_anchor_days INT NULL,
    ADD COLUMN gestational_age_anchor_date DATE NULL;

ALTER TABLE app.pregnancies ALTER COLUMN calculation_source TYPE VARCHAR(30);

ALTER TABLE app.pregnancies DROP CONSTRAINT ck_pregnancies_calculation_source;

ALTER TABLE app.pregnancies ADD CONSTRAINT ck_pregnancies_calculation_source
    CHECK (calculation_source IN (
        'LMP', 'EDD', 'LAST_MENSTRUAL_PERIOD', 'CONCEPTION_DATE',
        'ESTIMATED_DUE_DATE', 'ULTRASOUND', 'IVF', 'MANUAL'));

ALTER TABLE app.pregnancy_audits ADD COLUMN calculation_source VARCHAR(30) NULL;

