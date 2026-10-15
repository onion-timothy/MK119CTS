-- devices.name 을 단독 UNIQUE 로 전환
-- 기존 복합 UNIQUE (name, group_id) 제거 후 name 단독 UNIQUE 추가
-- 사전 조건: devices.name 에 전역 중복이 없어야 함
--   SELECT name, COUNT(*) FROM devices GROUP BY name HAVING COUNT(*) > 1;
-- 중복이 있으면 먼저 정리할 것

SET XACT_ABORT ON;
BEGIN TRANSACTION;

DECLARE @constraint_name sysname;

SELECT @constraint_name = kc.name
FROM sys.key_constraints kc
JOIN sys.index_columns ic
  ON ic.object_id = kc.parent_object_id
 AND ic.index_id  = kc.unique_index_id
JOIN sys.columns c
  ON c.object_id = ic.object_id
 AND c.column_id = ic.column_id
WHERE kc.parent_object_id = OBJECT_ID('dbo.devices')
  AND kc.type = 'UQ'
GROUP BY kc.name
HAVING COUNT(*) = 2
   AND SUM(CASE WHEN c.name IN ('name', 'group_id') THEN 1 ELSE 0 END) = 2;

IF @constraint_name IS NOT NULL
BEGIN
    DECLARE @sql nvarchar(max) =
        N'ALTER TABLE dbo.devices DROP CONSTRAINT ' + QUOTENAME(@constraint_name) + N';';
    EXEC sp_executesql @sql;
END

IF NOT EXISTS (
    SELECT 1
    FROM sys.key_constraints kc
    JOIN sys.index_columns ic
      ON ic.object_id = kc.parent_object_id
     AND ic.index_id  = kc.unique_index_id
    JOIN sys.columns c
      ON c.object_id = ic.object_id
     AND c.column_id = ic.column_id
    WHERE kc.parent_object_id = OBJECT_ID('dbo.devices')
      AND kc.type = 'UQ'
      AND c.name = 'name'
    GROUP BY kc.name
    HAVING COUNT(*) = 1
)
BEGIN
    ALTER TABLE dbo.devices
        ADD CONSTRAINT UK_devices_name UNIQUE (name);
END

COMMIT TRANSACTION;
