-- ============================================================
-- V1.3.1 SCIM 2.0 同步权限码
-- 本迁移只补权限码,不建表:SCIM 直接读写既有的 iam_user / iam_group,
-- 不引入第二套人员模型,避免同一份数据两处维护。
-- 语义:scim:manage 授权持有者按 RFC 7644 增删改查本域人员与组织;
--   建议只授予专用服务账号的角色,便于把系统同步与人工操作分开审计。
-- ============================================================

INSERT INTO iam_permission(realm_id, permission_code, permission_name, resource, action, description)
SELECT r.id, v.code, v.name, v.resource, v.action, v.descr
FROM iam_realm r
CROSS JOIN (
    VALUES
        ('scim:manage', 'SCIM 人员与组织同步', 'scim', 'manage',
         '按 SCIM 2.0 协议同步本域用户与用户组')
) AS v(code, name, resource, action, descr)
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_permission p
                  WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE r.realm_key = 'default'
  AND p.permission_code = 'scim:manage'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);

COMMENT ON COLUMN iam_permission.permission_code IS '权限码;scim:manage 用于 SCIM 2.0 人员与组织同步';
