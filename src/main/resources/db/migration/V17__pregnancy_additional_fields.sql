SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

ALTER TABLE app.pregnancies ADD
    is_first_pregnancy BIT NULL,
    multiple_pregnancy BIT NULL,
    timezone NVARCHAR(50) NULL;
GO
