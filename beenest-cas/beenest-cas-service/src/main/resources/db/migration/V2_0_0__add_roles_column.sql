-- ============================================================
-- V2.0.0: 新增 cas_user.roles 列
-- 存储用户应用角色（逗号分隔），用于 Service accessStrategy requiredAttributes 匹配
-- ============================================================
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS roles VARCHAR(500) DEFAULT NULL;
COMMENT ON COLUMN cas_user.roles IS '用户应用角色（逗号分隔），用于 Service accessStrategy requiredAttributes 匹配';
