:on error exit
:setvar DatabaseName "NutriMomDB"
:setvar AppLogin "nutrimom_app"
:setvar MigratorLogin "nutrimom_migrator"

USE [master];
GO

IF DB_ID(N'$(DatabaseName)') IS NULL
BEGIN
    EXEC(N'CREATE DATABASE [' + N'$(DatabaseName)' + N'] COLLATE Vietnamese_100_CI_AI_SC_UTF8');
END;
GO

ALTER DATABASE [$(DatabaseName)] SET RECOVERY SIMPLE;
ALTER DATABASE [$(DatabaseName)] SET ALLOW_SNAPSHOT_ISOLATION ON;
ALTER DATABASE [$(DatabaseName)] SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE;
ALTER DATABASE [$(DatabaseName)] SET QUERY_STORE = ON;
ALTER DATABASE [$(DatabaseName)] SET QUERY_STORE (
    OPERATION_MODE = READ_WRITE,
    CLEANUP_POLICY = (STALE_QUERY_THRESHOLD_DAYS = 30),
    MAX_STORAGE_SIZE_MB = 512,
    QUERY_CAPTURE_MODE = AUTO
);
GO

IF SUSER_ID(N'$(AppLogin)') IS NULL
BEGIN
    DECLARE @createAppLogin NVARCHAR(MAX) =
        N'CREATE LOGIN [' + N'$(AppLogin)' + N'] WITH PASSWORD=N''' + REPLACE(N'$(AppPassword)', '''', '''''') +
        N''', CHECK_POLICY=ON, CHECK_EXPIRATION=OFF, DEFAULT_DATABASE=[' + N'$(DatabaseName)' + N']';
    EXEC(@createAppLogin);
END;

IF SUSER_ID(N'$(MigratorLogin)') IS NULL
BEGIN
    DECLARE @createMigratorLogin NVARCHAR(MAX) =
        N'CREATE LOGIN [' + N'$(MigratorLogin)' + N'] WITH PASSWORD=N''' + REPLACE(N'$(MigratorPassword)', '''', '''''') +
        N''', CHECK_POLICY=ON, CHECK_EXPIRATION=OFF, DEFAULT_DATABASE=[' + N'$(DatabaseName)' + N']';
    EXEC(@createMigratorLogin);
END;
GO

USE [$(DatabaseName)];
GO

IF SCHEMA_ID(N'app') IS NULL EXEC(N'CREATE SCHEMA app AUTHORIZATION dbo');
GO

IF USER_ID(N'$(AppLogin)') IS NULL
    CREATE USER [$(AppLogin)] FOR LOGIN [$(AppLogin)] WITH DEFAULT_SCHEMA=[app];
IF USER_ID(N'$(MigratorLogin)') IS NULL
    CREATE USER [$(MigratorLogin)] FOR LOGIN [$(MigratorLogin)] WITH DEFAULT_SCHEMA=[app];
GO

IF IS_ROLEMEMBER(N'db_datareader', N'$(AppLogin)') <> 1
    ALTER ROLE [db_datareader] ADD MEMBER [$(AppLogin)];
IF IS_ROLEMEMBER(N'db_datawriter', N'$(AppLogin)') <> 1
    ALTER ROLE [db_datawriter] ADD MEMBER [$(AppLogin)];
GRANT EXECUTE ON SCHEMA::[app] TO [$(AppLogin)];

IF IS_ROLEMEMBER(N'db_ddladmin', N'$(MigratorLogin)') <> 1
    ALTER ROLE [db_ddladmin] ADD MEMBER [$(MigratorLogin)];
IF IS_ROLEMEMBER(N'db_datareader', N'$(MigratorLogin)') <> 1
    ALTER ROLE [db_datareader] ADD MEMBER [$(MigratorLogin)];
IF IS_ROLEMEMBER(N'db_datawriter', N'$(MigratorLogin)') <> 1
    ALTER ROLE [db_datawriter] ADD MEMBER [$(MigratorLogin)];
GRANT CREATE TABLE TO [$(MigratorLogin)];
GRANT CREATE VIEW TO [$(MigratorLogin)];
GRANT CREATE PROCEDURE TO [$(MigratorLogin)];
GO
