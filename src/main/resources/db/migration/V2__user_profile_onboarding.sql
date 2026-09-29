ALTER TABLE app.users
    ADD COLUMN email             VARCHAR(255) NULL,
    ADD COLUMN gender            VARCHAR(10)  NULL,
    ADD COLUMN date_of_birth     DATE          NULL,
    ADD COLUMN avatar_key        VARCHAR(50)  NULL,
    ADD COLUMN onboarding_status VARCHAR(30)  NOT NULL DEFAULT 'PROFILE_REQUIRED';

ALTER TABLE app.users ADD CONSTRAINT ck_users_gender
    CHECK (gender IN ('MALE', 'FEMALE', 'OTHER'));

ALTER TABLE app.users ADD CONSTRAINT ck_users_onboarding
    CHECK (onboarding_status IN ('PROFILE_REQUIRED', 'CONTEXT_REQUIRED', 'COMPLETED'));

-- Người dùng đã tồn tại trước khi có onboarding: coi như đã hoàn tất.
UPDATE app.users SET onboarding_status = 'COMPLETED';

