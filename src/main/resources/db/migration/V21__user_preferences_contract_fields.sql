ALTER TABLE app.user_preferences
    ADD COLUMN locale             VARCHAR(20) NOT NULL DEFAULT 'vi-VN',
    ADD COLUMN theme              VARCHAR(20) NOT NULL DEFAULT 'SYSTEM',
    ADD COLUMN weight_unit        VARCHAR(20) NOT NULL DEFAULT 'KG',
    ADD COLUMN length_unit        VARCHAR(20) NOT NULL DEFAULT 'CM',
    ADD COLUMN glucose_unit       VARCHAR(20) NOT NULL DEFAULT 'MMOL_L',
    ADD COLUMN backup_enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN quiet_hours_start  TIME NULL,
    ADD COLUMN quiet_hours_end    TIME NULL;

