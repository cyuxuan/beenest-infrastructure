-- 回滚 V1.1.5:撤销审计只追加约束、留存期索引与导出权限。
-- 注意:回滚后审计表重新允许 UPDATE/DELETE,审计记录的防篡改能力随之下线。
-- 触发器与函数必须成对删除,否则残留函数会被后续同名 CREATE OR REPLACE 覆盖,
--   造成"看起来已回滚、实际触发器仍在"的错觉。

-- 3. 导出权限:先删授予再删权限码
DELETE FROM iam_role_permission rp
USING iam_permission p
WHERE rp.permission_id = p.id
  AND p.permission_code = 'audit:export';

DELETE FROM iam_permission WHERE permission_code = 'audit:export';

-- 2. 留存期清理索引
DROP INDEX IF EXISTS idx_iam_audit_log_occurred_time;

-- 1. 只追加约束:先删触发器,再删函数
DROP TRIGGER IF EXISTS trg_iam_audit_log_append_only ON iam_audit_log;
DROP FUNCTION IF EXISTS iam_audit_log_append_only();
