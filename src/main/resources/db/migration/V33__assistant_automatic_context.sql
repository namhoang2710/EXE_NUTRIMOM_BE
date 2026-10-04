-- Account context is available automatically inside NutriMom. Cloud processing remains opt-in.
ALTER TABLE app.assistant_preferences ALTER COLUMN use_profile SET DEFAULT TRUE;
ALTER TABLE app.assistant_preferences ALTER COLUMN use_pregnancy SET DEFAULT TRUE;
ALTER TABLE app.assistant_preferences ALTER COLUMN use_medical_records SET DEFAULT TRUE;

-- Upgrade untouched defaults only. Preserve choices from an existing context revision.
UPDATE app.assistant_preferences
SET use_profile = TRUE, use_pregnancy = TRUE, use_medical_records = TRUE,
    context_version = context_version + 1, version = version + 1
WHERE context_version = 0 AND cloud_consent = FALSE
  AND use_profile = FALSE AND use_pregnancy = FALSE AND use_medical_records = FALSE;
