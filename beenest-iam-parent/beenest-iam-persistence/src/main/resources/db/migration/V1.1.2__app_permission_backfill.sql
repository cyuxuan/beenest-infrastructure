-- ============================================================
-- beenest-iam 应用权限回填迁移(V1.1.2)
-- 1. 补种 app-service:backfill 权限码:存量应用切换 SCOPED 前把
--    平台级权限/菜单迁到应用名下(归属迁移或复制到应用)
-- 2. 内置 SUPER_ADMIN 自动获得该权限
-- 说明:数据回填不在迁移里自动执行,必须先看回填报告再人工确认导入。
-- ============================================================

-- 1. 回填操作权限码
INSERT INTO iam_permission (realm_id, permission_code, permission_name, resource, action, description)
SELECT r.id, 'app-service:backfill', '执行应用权限回填', 'app-service', 'backfill',
       '把平台级权限与菜单迁到应用名下,供存量应用切换 SCOPED 令牌档'
FROM iam_realm r
WHERE NOT EXISTS (SELECT 1
                  FROM iam_permission p
                  WHERE p.realm_id = r.id
                    AND p.permission_code = 'app-service:backfill'
                    AND p.app_service_id IS NULL
                    AND NOT p.deleted);

-- 2. 内置超级管理员获得回填权限
INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
         JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
         JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE p.app_service_id IS NULL
  AND p.permission_code = 'app-service:backfill'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
