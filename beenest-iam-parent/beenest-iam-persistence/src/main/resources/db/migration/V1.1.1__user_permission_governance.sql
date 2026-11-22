-- ============================================================
-- beenest-iam 用户级权限治理迁移(V1.1.1)
-- 1. 每用户每权限只允许一条有效特批(GRANT / REVOKE 二选一)
-- 2. 记录操作人(granted_by),让特批关系本身可追溯
-- 3. 补种 user:grant-permission 权限码,内置 SUPER_ADMIN 自动获得
-- 说明:过期记录仍保留在表中供追溯,重新设置时覆盖同一条记录。
-- ============================================================

-- 1. 去重:历史重复记录保留最后更新的一条,其余逻辑删除
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY user_id, permission_id
               ORDER BY updated_time DESC, id DESC
           ) AS rn
    FROM iam_user_permission
    WHERE deleted = FALSE
)
UPDATE iam_user_permission up
SET deleted = TRUE,
    updated_time = CURRENT_TIMESTAMP
FROM ranked
WHERE up.id = ranked.id
  AND ranked.rn > 1;

-- 2. 同用户同权限只能有一个有效特批,避免 GRANT/REVOKE 互相覆盖产生歧义
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_user_permission
    ON iam_user_permission(user_id, permission_id)
    WHERE deleted = FALSE;

-- 3. 特批操作人:审计事件之外,授权关系行自身也保留来源
ALTER TABLE iam_user_permission ADD COLUMN IF NOT EXISTS granted_by BIGINT;
COMMENT ON COLUMN iam_user_permission.granted_by IS '最近一次设置该特批的操作人用户主键';

-- 4. 授权类型只有两种,错误值会让权限求值静默跳过,必须在库层拦截
ALTER TABLE iam_user_permission DROP CONSTRAINT IF EXISTS ck_iam_user_permission_grant_type;
ALTER TABLE iam_user_permission
    ADD CONSTRAINT ck_iam_user_permission_grant_type
        CHECK (grant_type IN ('GRANT', 'REVOKE'));

-- 5. 过期记录按到期时间清理/巡检
CREATE INDEX IF NOT EXISTS idx_iam_user_permission_expired
    ON iam_user_permission(expired_time)
    WHERE deleted = FALSE AND expired_time IS NOT NULL;

-- 6. 用户权限特批管理权限码
INSERT INTO iam_permission (realm_id, permission_code, permission_name, resource, action, description)
SELECT r.id, 'user:grant-permission', '管理用户权限特批', 'user', 'grant-permission',
       '为用户授予或撤销单项权限;变更在账号下一次认证时生效'
FROM iam_realm r
WHERE NOT EXISTS (SELECT 1
                  FROM iam_permission p
                  WHERE p.realm_id = r.id
                    AND p.permission_code = 'user:grant-permission'
                    AND p.app_service_id IS NULL
                    AND NOT p.deleted);

-- 7. 内置超级管理员获得新增权限
INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
         JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
         JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE p.app_service_id IS NULL
  AND p.permission_code = 'user:grant-permission'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
