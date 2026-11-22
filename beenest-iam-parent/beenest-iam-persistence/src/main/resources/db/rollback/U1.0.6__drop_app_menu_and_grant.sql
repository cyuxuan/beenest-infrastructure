-- ============================================================
-- 回滚 V1.0.6(应用菜单与授权)
-- 逆序执行:先解角色授权 → 删权限码 → 还原菜单唯一索引 → 删列。
-- 注意:还原 uk_iam_menu_code 要求同域内菜单编码本身唯一;若已按应用
--       分域重名(如两个应用都有 dashboard),该索引会创建失败,
--       此时应先人工归并重名菜单编码再回滚。
-- ============================================================

DELETE FROM iam_role_permission rp
USING iam_permission p
WHERE rp.permission_id = p.id
  AND p.permission_code IN ('menu:view', 'menu:create', 'menu:update', 'menu:delete',
                            'role:assign-menu', 'app-service:assign');

DELETE FROM iam_permission
WHERE permission_code IN ('menu:view', 'menu:create', 'menu:update', 'menu:delete',
                          'role:assign-menu', 'app-service:assign');

DROP INDEX IF EXISTS uk_iam_menu_app_code;
DROP INDEX IF EXISTS idx_iam_menu_app;
CREATE UNIQUE INDEX uk_iam_menu_code ON iam_menu(realm_id, menu_code) WHERE deleted = FALSE;

DROP INDEX IF EXISTS idx_iam_permission_app;
ALTER TABLE iam_permission DROP COLUMN IF EXISTS app_service_id;
ALTER TABLE iam_menu DROP COLUMN IF EXISTS app_service_id;
