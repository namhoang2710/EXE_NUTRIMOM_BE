CREATE TABLE app.verified_guidance (
    id VARCHAR(36) NOT NULL,
    week INT NULL,
    topic VARCHAR(100) NULL,
    locale VARCHAR(10) NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    source_name VARCHAR(255) NOT NULL,
    source_url VARCHAR(1000) NULL,
    reviewer VARCHAR(255) NOT NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_review_at TIMESTAMP WITH TIME ZONE NULL,
    evidence_level VARCHAR(50) NULL,
    disclaimer TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_verified_guidance PRIMARY KEY (id),
    CONSTRAINT ck_verified_guidance_week CHECK (week IS NULL OR week BETWEEN 0 AND 42)
);

CREATE INDEX ix_verified_guidance_filter
    ON app.verified_guidance(week, topic, locale, created_at);

CREATE TABLE app.preparation_items (
    id VARCHAR(36) NOT NULL,
    pregnancy_id VARCHAR(36) NOT NULL,
    group_code VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    sort_order INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_preparation_items PRIMARY KEY (id),
    CONSTRAINT fk_preparation_items_pregnancy FOREIGN KEY (pregnancy_id)
        REFERENCES app.pregnancies(id)
);

CREATE INDEX ix_preparation_items_pregnancy_order
    ON app.preparation_items(pregnancy_id, sort_order);

CREATE TABLE app.birth_plans (
    id VARCHAR(36) NOT NULL,
    pregnancy_id VARCHAR(36) NOT NULL,
    companion VARCHAR(255) NULL,
    preferred_facility VARCHAR(255) NULL,
    pain_management_note TEXT NULL,
    newborn_care_note TEXT NULL,
    free_text_note TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_birth_plans PRIMARY KEY (id),
    CONSTRAINT fk_birth_plans_pregnancy FOREIGN KEY (pregnancy_id)
        REFERENCES app.pregnancies(id),
    CONSTRAINT ux_birth_plans_pregnancy UNIQUE (pregnancy_id)
);

