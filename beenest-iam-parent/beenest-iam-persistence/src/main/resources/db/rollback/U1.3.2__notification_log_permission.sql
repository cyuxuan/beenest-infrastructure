-- 回滚 V1.3.2:移除通知投递记录查询权限码及其角色授予。
-- 先删授予再删权限码,避免残留指向已删权限的授予行。

DELETE FROM iam_role_permission rp
USING iam_permission p
WHERE rp.permission_id = p.id
  AND p.permission_code = 'notification:view';

DELETE FROM iam_permission WHERE permission_code = 'notification:view';

COMMENT ON COLUMN iam_permission.permission_code IS '权限码';
