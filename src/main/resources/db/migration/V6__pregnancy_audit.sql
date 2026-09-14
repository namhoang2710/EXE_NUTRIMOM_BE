SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

CREATE TABLE app.pregnancy_audits (
    id NVARCHAR(36) NOT NULL,
    pregnancy_id NVARCHAR(36) NOT NULL,
    user_id NVARCHAR(36) NOT NULL,
    event_type NVARCHAR(40) NOT NULL,
    previous_due_date DATE NULL,
    new_due_date DATE NULL,
    created_at DATETIMEOFFSET(7) NOT NULL,
    CONSTRAINT pk_pregnancy_audits PRIMARY KEY (id),
    CONSTRAINT fk_pregnancy_audits_pregnancy FOREIGN KEY (pregnancy_id)
        REFERENCES app.pregnancies(id),
    CONSTRAINT fk_pregnancy_audits_user FOREIGN KEY (user_id)
        REFERENCES app.users(id),
    CONSTRAINT ck_pregnancy_audits_event_type CHECK (event_type IN ('DUE_DATE_CHANGED'))
);
GO

CREATE INDEX ix_pregnancy_audits_pregnancy_created
    ON app.pregnancy_audits(pregnancy_id, created_at DESC);
GO