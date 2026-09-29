SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

CREATE TABLE app.verified_guidance (
    id NVARCHAR(36) NOT NULL,
    week INT NULL,
    topic NVARCHAR(100) NULL,
    locale NVARCHAR(10) NOT NULL,
    title NVARCHAR(255) NOT NULL,
    summary NVARCHAR(MAX) NOT NULL,
    source_name NVARCHAR(255) NOT NULL,
    source_url NVARCHAR(1000) NULL,
    reviewer NVARCHAR(255) NOT NULL,
    reviewed_at DATETIMEOFFSET(7) NOT NULL,
    next_review_at DATETIMEOFFSET(7) NULL,
    evidence_level NVARCHAR(50) NULL,
    disclaimer NVARCHAR(MAX) NOT NULL,
    created_at DATETIMEOFFSET(7) NOT NULL,
    updated_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_verified_guidance PRIMARY KEY (id),
    CONSTRAINT ck_verified_guidance_week CHECK (week IS NULL OR week BETWEEN 0 AND 42)
);
GO

CREATE INDEX ix_verified_guidance_filter
    ON app.verified_guidance(week, topic, locale, created_at);
GO

CREATE TABLE app.preparation_items (
    id NVARCHAR(36) NOT NULL,
    pregnancy_id NVARCHAR(36) NOT NULL,
    group_code NVARCHAR(50) NOT NULL,
    title NVARCHAR(255) NOT NULL,
    completed BIT NOT NULL CONSTRAINT df_preparation_items_completed DEFAULT 0,
    completed_at DATETIMEOFFSET(7) NULL,
    sort_order INT NOT NULL,
    version BIGINT NOT NULL CONSTRAINT df_preparation_items_version DEFAULT 0,
    created_at DATETIMEOFFSET(7) NOT NULL,
    updated_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_preparation_items PRIMARY KEY (id),
    CONSTRAINT fk_preparation_items_pregnancy FOREIGN KEY (pregnancy_id)
        REFERENCES app.pregnancies(id)
);
GO

CREATE INDEX ix_preparation_items_pregnancy_order
    ON app.preparation_items(pregnancy_id, sort_order);
GO

CREATE TABLE app.birth_plans (
    id NVARCHAR(36) NOT NULL,
    pregnancy_id NVARCHAR(36) NOT NULL,
    companion NVARCHAR(255) NULL,
    preferred_facility NVARCHAR(255) NULL,
    pain_management_note NVARCHAR(MAX) NULL,
    newborn_care_note NVARCHAR(MAX) NULL,
    free_text_note NVARCHAR(MAX) NULL,
    version BIGINT NOT NULL CONSTRAINT df_birth_plans_version DEFAULT 0,
    created_at DATETIMEOFFSET(7) NOT NULL,
    updated_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_birth_plans PRIMARY KEY (id),
    CONSTRAINT fk_birth_plans_pregnancy FOREIGN KEY (pregnancy_id)
        REFERENCES app.pregnancies(id),
    CONSTRAINT ux_birth_plans_pregnancy UNIQUE (pregnancy_id)
);
GO
