SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

ALTER TABLE app.pregnancy_week_contents ADD
    review_status       NVARCHAR(20) NOT NULL CONSTRAINT df_pregnancy_content_review_status DEFAULT 'UNREVIEWED',
    reviewed_by         NVARCHAR(255) NULL,
    reviewed_at         DATETIMEOFFSET(7) NULL,
    next_review_at      DATETIMEOFFSET(7) NULL,
    content_version     INT NOT NULL CONSTRAINT df_pregnancy_content_version DEFAULT 1,
    baby_length_cm_min  DECIMAL(6,2) NULL,
    baby_length_cm_max  DECIMAL(6,2) NULL,
    baby_weight_g_min   INT NULL,
    baby_weight_g_max   INT NULL,
    comparison_label    NVARCHAR(200) NULL;
GO

ALTER TABLE app.pregnancy_week_contents ADD CONSTRAINT ck_pregnancy_content_review_status
    CHECK (review_status IN ('UNREVIEWED', 'REVIEWED'));
GO
