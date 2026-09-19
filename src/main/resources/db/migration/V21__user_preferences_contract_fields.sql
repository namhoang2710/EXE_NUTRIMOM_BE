SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

ALTER TABLE app.user_preferences ADD
    locale             NVARCHAR(20) NOT NULL CONSTRAINT df_user_preferences_locale DEFAULT 'vi-VN',
    theme              NVARCHAR(20) NOT NULL CONSTRAINT df_user_preferences_theme DEFAULT 'SYSTEM',
    weight_unit        NVARCHAR(20) NOT NULL CONSTRAINT df_user_preferences_weight_unit DEFAULT 'KG',
    length_unit        NVARCHAR(20) NOT NULL CONSTRAINT df_user_preferences_length_unit DEFAULT 'CM',
    glucose_unit       NVARCHAR(20) NOT NULL CONSTRAINT df_user_preferences_glucose_unit DEFAULT 'MMOL_L',
    backup_enabled     BIT NOT NULL CONSTRAINT df_user_preferences_backup DEFAULT 0,
    quiet_hours_start  TIME NULL,
    quiet_hours_end    TIME NULL;
GO
