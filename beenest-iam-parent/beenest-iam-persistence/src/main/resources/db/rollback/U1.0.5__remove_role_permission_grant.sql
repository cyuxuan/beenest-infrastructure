-- ============================================================
-- 回滚 V1.0.5:移除 role:assign-permission 权限码及其角色关联
-- 只删除本次新增的权限码,不触碰其他授权数据。
-- ============================================================

DELETE FROM iam_role_permission
WHERE permission_id IN (SELECT id FROM iam_permission WHERE permission_code = 'role:assign-permission');

DELETE FROM iam_permission WHERE permission_code = 'role:assign-permission';
