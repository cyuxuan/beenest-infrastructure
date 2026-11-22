-- ============================================================
-- beenest-iam 角色授权迁移(V1.0.5)
-- 1. 新增权限码 role:assign-permission(角色-权限直接授予)
-- 2. 内置 SUPER_ADMIN 自动获得该权限(幂等)
-- 说明:角色-权限直接关联表(iam_role_permission)在 V1.0.0 已建立,
--       本次只补"授予动作"的权限码,不新增表结构。
-- ============================================================

INSERT INTO iam_permission (realm_id, permission_code, permission_name, resource, action, description)
SELECT r.id, v.code, v.name, v.resource, v.action, v.description
FROM iam_realm r
         CROSS JOIN (VALUES
    ('role:assign-permission', '分配角色权限', 'role', 'assign-permission', '整体覆盖角色的直接权限集合')
) AS v(code, name, resource, action, description)
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_permission p
                  WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

-- 内置超级管理员获得新增权限:否则升级后无人能给角色授权
INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
         JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
         JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE r.realm_key = 'default'
  AND p.permission_code = 'role:assign-permission'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
