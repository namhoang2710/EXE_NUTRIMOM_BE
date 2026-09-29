CREATE TABLE app.pregnancy_week_contents (
    week INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    summary TEXT NOT NULL,
    baby_development TEXT NOT NULL,
    mother_changes TEXT NOT NULL,
    care_tips TEXT NOT NULL,
    warning_signs TEXT NOT NULL,
    sources TEXT NOT NULL,
    disclaimer TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_pregnancy_week_contents PRIMARY KEY (week),
    CONSTRAINT ck_pregnancy_week_contents_week CHECK (week BETWEEN 0 AND 42)
);

