-- ============================================================
-- 回滚 V1.2.0(组织架构与岗位)
-- 逆序执行:先撤权限码 → 删岗位表 → 撤任职列 → 撤组类型列。
-- 注意:回滚后岗位携带的角色会一次性从成员身上消失(权限收缩),
--       若已有账号靠岗位角色维持访问,需先改为直授或组角色再回滚。
-- ============================================================

DELETE FROM iam_role_permission
WHERE permission_id IN (SELECT id FROM iam_permission
                        WHERE permission_code IN ('position:view', 'position:create',
                                                  'position:update', 'position:delete',
                                                  'position:assign-role'));

DELETE FROM iam_permission
WHERE permission_code IN ('position:view', 'position:create',
                          'position:update', 'position:delete', 'position:assign-role');

DROP INDEX IF EXISTS uk_iam_position_role;
DROP INDEX IF EXISTS idx_iam_position_role_role;
DROP TABLE IF EXISTS iam_position_role;

DROP INDEX IF EXISTS uk_iam_position_code;
DROP INDEX IF EXISTS idx_iam_position_realm;
DROP TABLE IF EXISTS iam_position;

DROP INDEX IF EXISTS uk_iam_user_group_primary;
DROP INDEX IF EXISTS idx_iam_user_group_position;
ALTER TABLE iam_user_group DROP COLUMN IF EXISTS is_primary;
ALTER TABLE iam_user_group DROP COLUMN IF EXISTS position_id;

DROP INDEX IF EXISTS idx_iam_group_type;
ALTER TABLE iam_group DROP CONSTRAINT IF EXISTS ck_iam_group_type;
ALTER TABLE iam_group DROP COLUMN IF EXISTS external_id;
ALTER TABLE iam_group DROP COLUMN IF EXISTS sort_order;
ALTER TABLE iam_group DROP COLUMN IF EXISTS leader_user_id;
ALTER TABLE iam_group DROP COLUMN IF EXISTS group_type;
