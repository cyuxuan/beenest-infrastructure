-- 回滚 V1.0.1:删除种子数据(幂等)
DELETE FROM iam_role_permission
WHERE role_id IN (SELECT id FROM iam_role WHERE role_code = 'SUPER_ADMIN');
DELETE FROM iam_permission WHERE realm_id IN (SELECT id FROM iam_realm WHERE realm_key = 'default');
DELETE FROM iam_role WHERE role_code = 'SUPER_ADMIN';
DELETE FROM iam_client_scope WHERE realm_id IN (SELECT id FROM iam_realm WHERE realm_key = 'default');
DELETE FROM iam_client WHERE client_key = 'iam-admin-web';
DELETE FROM iam_realm WHERE realm_key = 'default';
