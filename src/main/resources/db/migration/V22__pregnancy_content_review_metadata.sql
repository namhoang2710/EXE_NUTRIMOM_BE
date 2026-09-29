ALTER TABLE app.pregnancy_week_contents
    ADD COLUMN review_status       VARCHAR(20) NOT NULL DEFAULT 'UNREVIEWED',
    ADD COLUMN reviewed_by         VARCHAR(255) NULL,
    ADD COLUMN reviewed_at         TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN next_review_at      TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN content_version     INT NOT NULL DEFAULT 1,
    ADD COLUMN baby_length_cm_min  DECIMAL(6,2) NULL,
    ADD COLUMN baby_length_cm_max  DECIMAL(6,2) NULL,
    ADD COLUMN baby_weight_g_min   INT NULL,
    ADD COLUMN baby_weight_g_max   INT NULL,
    ADD COLUMN comparison_label    VARCHAR(200) NULL;

ALTER TABLE app.pregnancy_week_contents ADD CONSTRAINT ck_pregnancy_content_review_status
    CHECK (review_status IN ('UNREVIEWED', 'REVIEWED'));

