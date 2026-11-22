-- 回滚 V1.0.9:撤销应用作用域授权。
-- 按迁移的逆序执行:先删种子数据,再删约束与索引,最后删列与表。
-- 不可逆部分:迁移第 6 步把存量 CLIENT 角色的 app_service_id 回填过一次,
--   回滚删列后该归属关系一并丢失,重新升级时由迁移重跑一次即可重新回填。
-- 唯一索引重建可能因存量数据冲突而失败,脚本先做冲突检测并给出明确提示,
--   避免 psql 抛出难以定位的 duplicate key 错误。

-- ------------------------------------------------------------
-- 8-9. 撤销权限点种子:先删授予,再删权限码
-- ------------------------------------------------------------
DELETE FROM iam_role_permission rp
USING iam_permission p
WHERE rp.permission_id = p.id
  AND p.app_service_id IS NULL
  AND p.permission_code IN ('permission:create', 'permission:update', 'permission:delete');

DELETE FROM iam_permission
WHERE app_service_id IS NULL
  AND permission_code IN ('permission:create', 'permission:update', 'permission:delete');

-- ------------------------------------------------------------
-- 7. 应用授权名单去重约束
-- ------------------------------------------------------------
DROP INDEX IF EXISTS uk_iam_app_service_grant;

-- ------------------------------------------------------------
-- 5. 应用管理员与应用角色映射表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS iam_app_role_mapping;
DROP TABLE IF EXISTS iam_app_admin;

-- ------------------------------------------------------------
-- 4. 一个客户端只绑定一个应用
-- ------------------------------------------------------------
DROP INDEX IF EXISTS uk_iam_app_service_client;

-- ------------------------------------------------------------
-- 3. 角色应用归属:先恢复原唯一索引,再删列
-- ------------------------------------------------------------
-- 原 uk_iam_role_scope 不含 app_service_id;若同一 (realm, container, client, role_code)
-- 已存在多条有效角色,重建必然冲突,此时必须由人工先清理数据。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM iam_role
        WHERE deleted = FALSE
        GROUP BY realm_id, container_type, COALESCE(client_id, 0), role_code
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION '无法回滚 V1.0.9:iam_role 中存在同 (realm_id, container_type, client_id, role_code) 的多条有效角色,重建 uk_iam_role_scope 会冲突;请先清理重复角色后重试'
            USING ERRCODE = 'unique_violation';
    END IF;
END $$;

DROP INDEX IF EXISTS uk_iam_role_scope;
CREATE UNIQUE INDEX uk_iam_role_scope
    ON iam_role(realm_id, container_type, COALESCE(client_id, 0), role_code)
    WHERE deleted = FALSE;

DROP INDEX IF EXISTS idx_iam_role_app;
ALTER TABLE iam_role DROP COLUMN IF EXISTS app_service_id;

-- ------------------------------------------------------------
-- 2. 权限命名空间:恢复平台与应用共用一条唯一约束的旧形态
-- ------------------------------------------------------------
DROP INDEX IF EXISTS uk_iam_menu_platform_code;
DROP INDEX IF EXISTS uk_iam_permission_app_code;
DROP INDEX IF EXISTS uk_iam_permission_platform_code;

-- 旧 uk_iam_permission_code 不区分平台/应用,跨应用复用同一权限码会冲突
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM iam_permission
        WHERE deleted = FALSE
        GROUP BY realm_id, permission_code
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION '无法回滚 V1.0.9:iam_permission 中存在同 (realm_id, permission_code) 的多条有效权限,重建 uk_iam_permission_code 会冲突;请先清理跨应用复用的权限码后重试'
            USING ERRCODE = 'unique_violation';
    END IF;
END $$;

CREATE UNIQUE INDEX uk_iam_permission_code
    ON iam_permission(realm_id, permission_code)
    WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 1. 应用授权模式与令牌兼容档
-- ------------------------------------------------------------
DROP INDEX IF EXISTS idx_iam_app_service_authorization;
ALTER TABLE iam_application_service DROP COLUMN IF EXISTS token_profile;
ALTER TABLE iam_application_service DROP COLUMN IF EXISTS authorization_mode;
