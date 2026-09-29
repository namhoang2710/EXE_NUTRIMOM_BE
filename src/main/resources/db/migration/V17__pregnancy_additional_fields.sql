ALTER TABLE app.pregnancies
    ADD COLUMN is_first_pregnancy BOOLEAN NULL,
    ADD COLUMN multiple_pregnancy BOOLEAN NULL,
    ADD COLUMN timezone VARCHAR(50) NULL;

