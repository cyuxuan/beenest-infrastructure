-- 回滚 V1.1.2:撤销应用权限回填权限码。
-- 只涉及种子数据,先删授予再删权限码。

DELETE FROM iam_role_permission rp
USING iam_permission p
WHERE rp.permission_id = p.id
  AND p.app_service_id IS NULL
  AND p.permission_code = 'app-service:backfill';

DELETE FROM iam_permission
WHERE app_service_id IS NULL
  AND permission_code = 'app-service:backfill';
