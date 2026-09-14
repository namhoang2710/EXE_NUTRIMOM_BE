SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

CREATE TABLE app.pregnancy_week_contents (
    week INT NOT NULL,
    title NVARCHAR(200) NOT NULL,
    summary NVARCHAR(MAX) NOT NULL,
    baby_development NVARCHAR(MAX) NOT NULL,
    mother_changes NVARCHAR(MAX) NOT NULL,
    care_tips NVARCHAR(MAX) NOT NULL,
    warning_signs NVARCHAR(MAX) NOT NULL,
    sources NVARCHAR(MAX) NOT NULL,
    disclaimer NVARCHAR(MAX) NOT NULL,
    created_at DATETIMEOFFSET(7) NOT NULL,
    updated_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_pregnancy_week_contents PRIMARY KEY (week),
    CONSTRAINT ck_pregnancy_week_contents_week CHECK (week BETWEEN 0 AND 42)
);
GO
