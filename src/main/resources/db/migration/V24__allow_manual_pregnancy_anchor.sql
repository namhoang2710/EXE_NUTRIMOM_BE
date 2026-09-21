SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

ALTER TABLE app.pregnancies ALTER COLUMN estimated_due_date DATE NULL;
GO

ALTER TABLE app.pregnancies ALTER COLUMN last_menstrual_period DATE NULL;
GO
