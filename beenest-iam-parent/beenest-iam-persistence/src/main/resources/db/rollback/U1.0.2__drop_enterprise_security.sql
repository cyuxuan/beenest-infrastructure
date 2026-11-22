-- ============================================================
-- 回滚 V1.0.2(企业级能力迁移)
-- 按依赖逆序删除,仅清理 V1.0.2 实际插入的种子权限
-- (attribute-def:view / idp:view 由 V1.0.1 播种且被 NOT EXISTS 守卫跳过,不在清理范围)。
-- ============================================================
DELETE FROM iam_role_permission rp
USING iam_permission p
WHERE rp.permission_id = p.id
  AND p.permission_code IN ('group:view','group:create','group:update','group:delete','group:assign-user','group:assign-role',
                            'attribute-def:create','attribute-def:update','attribute-def:delete',
                            'protocol-mapper:view','protocol-mapper:create','protocol-mapper:update','protocol-mapper:delete',
                            'client-scope:view','client-scope:create','client-scope:update','client-scope:delete',
                            'idp:create','idp:update','idp:delete',
                            'idp-mapper:view','idp-mapper:create','idp-mapper:update','idp-mapper:delete',
                            'security-policy:view','security-policy:update','mfa:view','mfa:update');

DELETE FROM iam_permission
WHERE permission_code IN ('group:view','group:create','group:update','group:delete','group:assign-user','group:assign-role',
                          'attribute-def:create','attribute-def:update','attribute-def:delete',
                          'protocol-mapper:view','protocol-mapper:create','protocol-mapper:update','protocol-mapper:delete',
                          'client-scope:view','client-scope:create','client-scope:update','client-scope:delete',
                          'idp:create','idp:update','idp:delete',
                          'idp-mapper:view','idp-mapper:create','idp-mapper:update','idp-mapper:delete',
                          'security-policy:view','security-policy:update','mfa:view','mfa:update');

DROP TABLE IF EXISTS iam_user_password_history;
DROP TABLE IF EXISTS iam_login_policy;
DROP TABLE IF EXISTS iam_password_policy;
DROP TABLE IF EXISTS iam_group_role;
