SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

CREATE TABLE app.user_preferences (
    user_id NVARCHAR(36) NOT NULL,
    language NVARCHAR(10) NOT NULL CONSTRAINT df_user_preferences_language DEFAULT 'vi',
    timezone NVARCHAR(50) NOT NULL CONSTRAINT df_user_preferences_timezone DEFAULT 'Asia/Ho_Chi_Minh',
    notification_enabled BIT NOT NULL CONSTRAINT df_user_preferences_notification DEFAULT 1,
    push_enabled BIT NOT NULL CONSTRAINT df_user_preferences_push DEFAULT 1,
    email_enabled BIT NOT NULL CONSTRAINT df_user_preferences_email DEFAULT 0,
    sms_enabled BIT NOT NULL CONSTRAINT df_user_preferences_sms DEFAULT 0,
    preferred_reminder_time TIME NULL,
    version BIGINT NOT NULL CONSTRAINT df_user_preferences_version DEFAULT 0,
    created_at DATETIMEOFFSET(7) NOT NULL,
    updated_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_user_preferences PRIMARY KEY (user_id),
    CONSTRAINT fk_user_preferences_user FOREIGN KEY (user_id)
        REFERENCES app.users(id) ON DELETE CASCADE
);
GO
