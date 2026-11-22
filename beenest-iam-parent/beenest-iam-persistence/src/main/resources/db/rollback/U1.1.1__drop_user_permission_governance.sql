-- 回滚 V1.1.1:撤销用户级权限治理。
-- 不可逆部分:迁移第 1 步曾把重复的特批记录逻辑删除(只保留最后更新的一条),
--   被逻辑删除的行无法自动恢复。若确需找回,只能从备份还原。
-- 其余变更(唯一索引、granted_by 列、检查约束、过期索引、权限码)全部可逆。

-- 6-7. 撤销权限点种子:先删授予,再删权限码
DELETE FROM iam_role_permission rp
USING iam_permission p
WHERE rp.permission_id = p.id
  AND p.app_service_id IS NULL
  AND p.permission_code = 'user:grant-permission';

DELETE FROM iam_permission
WHERE app_service_id IS NULL
  AND permission_code = 'user:grant-permission';

-- 5. 过期记录巡检索引
DROP INDEX IF EXISTS idx_iam_user_permission_expired;

-- 4. 授权类型检查约束
ALTER TABLE iam_user_permission DROP CONSTRAINT IF EXISTS ck_iam_user_permission_grant_type;

-- 3. 特批操作人列
ALTER TABLE iam_user_permission DROP COLUMN IF EXISTS granted_by;

-- 2. 同用户同权限唯一约束
DROP INDEX IF EXISTS uk_iam_user_permission;
