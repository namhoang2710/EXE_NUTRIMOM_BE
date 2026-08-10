SET NOCOUNT ON;

SELECT DB_NAME() AS database_name, SUSER_SNAME() AS login_name,
       USER_NAME() AS database_user, SCHEMA_NAME() AS default_schema;

SELECT name, recovery_model_desc, is_read_committed_snapshot_on,
       snapshot_isolation_state_desc, is_query_store_on, collation_name
FROM sys.databases
WHERE name = DB_NAME();

SELECT s.name AS schema_name, t.name AS table_name
FROM sys.tables t
JOIN sys.schemas s ON s.schema_id = t.schema_id
WHERE s.name = N'app'
ORDER BY t.name;

SELECT dp.name AS database_user, dp.default_schema_name, rp.name AS database_role
FROM sys.database_principals dp
LEFT JOIN sys.database_role_members drm ON drm.member_principal_id = dp.principal_id
LEFT JOIN sys.database_principals rp ON rp.principal_id = drm.role_principal_id
WHERE dp.name IN (N'nutrimom_app', N'nutrimom_migrator')
ORDER BY dp.name, rp.name;
