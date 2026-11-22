-- ============================================================
-- 回滚 V1.0.3(管理运维能力迁移)
-- 按依赖逆序清理:先断角色关联,再删权限,最后删表。
-- ============================================================
DELETE FROM iam_role_permission rp
USING iam_permission p
WHERE rp.permission_id = p.id
  AND p.permission_code IN ('user:lock','user:logout','user:batch-status',
                            'token:view','token:revoke',
                            'consent:view','consent:revoke',
                            'login-event:view','dashboard:view',
                            'realm-key:view','realm-key:rotate');

DELETE FROM iam_permission
WHERE permission_code IN ('user:lock','user:logout','user:batch-status',
                          'token:view','token:revoke',
                          'consent:view','consent:revoke',
                          'login-event:view','dashboard:view',
                          'realm-key:view','realm-key:rotate');

DROP TABLE IF EXISTS iam_login_event;
