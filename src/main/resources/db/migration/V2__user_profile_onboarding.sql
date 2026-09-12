SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

ALTER TABLE app.users ADD
    email             NVARCHAR(255) NULL,
    gender            NVARCHAR(10)  NULL,
    date_of_birth     DATE          NULL,
    avatar_key        NVARCHAR(50)  NULL,
    onboarding_status NVARCHAR(30)  NOT NULL
        CONSTRAINT df_users_onboarding DEFAULT 'PROFILE_REQUIRED';
GO

ALTER TABLE app.users ADD CONSTRAINT ck_users_gender
    CHECK (gender IN ('MALE', 'FEMALE', 'OTHER'));
GO

ALTER TABLE app.users ADD CONSTRAINT ck_users_onboarding
    CHECK (onboarding_status IN ('PROFILE_REQUIRED', 'CONTEXT_REQUIRED', 'COMPLETED'));
GO

-- Người dùng đã tồn tại trước khi có onboarding: coi như đã hoàn tất.
UPDATE app.users SET onboarding_status = 'COMPLETED';
GO
