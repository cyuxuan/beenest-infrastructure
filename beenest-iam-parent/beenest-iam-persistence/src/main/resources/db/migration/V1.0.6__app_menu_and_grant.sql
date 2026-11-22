-- ============================================================
-- beenest-iam 应用菜单与授权迁移(V1.0.6)
-- 1. iam_menu 增加 app_service_id:菜单按应用归属,同一应用编码空间独立
-- 2. iam_permission 增加 app_service_id:权限按应用归属,便于按应用筛选
-- 3. 补种权限码:菜单 CRUD、角色-菜单授予、应用授权名单维护
-- 4. 内置 SUPER_ADMIN 自动获得新增权限(幂等)
-- 说明:iam_role_menu / iam_app_service_grant 表结构在 V1.0.0 已建立,
--       此前只有读路径;本次补齐写路径所需的数据结构与权限码。
-- ============================================================

-- 1. 菜单归属应用
ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS app_service_id BIGINT;
COMMENT ON COLUMN iam_menu.app_service_id IS '所属应用服务;为空表示平台级公共菜单(不参与应用菜单下发)';

-- 唯一性从"域 + 编码"收敛为"域 + 应用 + 编码":
-- 多应用共用一套编码(如 dashboard)时必须在应用内唯一即可
DROP INDEX IF EXISTS uk_iam_menu_code;
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_menu_app_code
    ON iam_menu(realm_id, app_service_id, menu_code) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_iam_menu_app
    ON iam_menu(realm_id, app_service_id) WHERE deleted = FALSE;

-- 2. 应用声明菜单托管模式:不是所有业务系统的菜单都该进 IAM
ALTER TABLE iam_application_service ADD COLUMN IF NOT EXISTS menu_mode VARCHAR(20) NOT NULL DEFAULT 'HYBRID';
COMMENT ON COLUMN iam_application_service.menu_mode IS
        '菜单托管模式:IAM 全量托管 / HYBRID 仅一级模块由 IAM 管 / APP 菜单由应用自管,只认权限码';

-- 3. 权限归属应用
ALTER TABLE iam_permission ADD COLUMN IF NOT EXISTS app_service_id BIGINT;
COMMENT ON COLUMN iam_permission.app_service_id IS '所属应用服务;为空表示平台级权限';
CREATE INDEX IF NOT EXISTS idx_iam_permission_app
    ON iam_permission(realm_id, app_service_id) WHERE deleted = FALSE;

-- 4. 权限码补种
INSERT INTO iam_permission (realm_id, permission_code, permission_name, resource, action, description)
SELECT r.id, v.code, v.name, v.resource, v.action, v.description
FROM iam_realm r
         CROSS JOIN (VALUES
    ('menu:view', '查看菜单', 'menu', 'view', '浏览应用菜单树'),
    ('menu:create', '新建菜单', 'menu', 'create', '在应用下新建菜单或按钮'),
    ('menu:update', '修改菜单', 'menu', 'update', '修改菜单名称/路径/排序与启用状态'),
    ('menu:delete', '删除菜单', 'menu', 'delete', '删除菜单及其子项'),
    ('role:assign-menu', '授予角色菜单', 'role', 'assign-menu', '整体覆盖角色的菜单集合'),
    ('app-service:assign', '维护应用授权', 'app-service', 'assign', '维护应用的角色/用户授权名单')
) AS v(code, name, resource, action, description)
WHERE NOT EXISTS (SELECT 1 FROM iam_permission p
                  WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

-- 4. 内置超级管理员获得新增权限
INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
         JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
         JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE p.permission_code IN ('menu:view', 'menu:create', 'menu:update', 'menu:delete',
                            'role:assign-menu', 'app-service:assign')
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
