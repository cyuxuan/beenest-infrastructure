-- ============================================================
-- beenest-iam 应用作用域授权迁移(V1.0.9)
-- 1. 应用声明授权模式(authorization_mode)与令牌收敛档(token_profile)
-- 2. 权限命名空间:平台权限与各应用权限各自唯一,允许不同应用复用同一权限码
-- 3. 角色增加应用归属:APP 容器角色与历史 CLIENT 角色均可挂到应用
-- 4. 一个客户端只绑定一个应用;应用授权名单去重约束
-- 5. 应用管理员与应用角色映射表(为后续应用自治授权铺路)
-- 6. 补种权限点写操作权限码,内置 SUPER_ADMIN 自动获得
-- 说明:本版 token_profile 默认仍为 LEGACY_GLOBAL,令牌裁剪在后续版本
--       实现后再切换 SCOPED 默认值,避免出现"标记已收敛但实际未裁剪"的假象。
-- ============================================================

-- 1. 应用授权模式与令牌兼容档
ALTER TABLE iam_application_service
    ADD COLUMN IF NOT EXISTS authorization_mode VARCHAR(20) NOT NULL DEFAULT 'IAM_MANAGED';
ALTER TABLE iam_application_service
    ADD COLUMN IF NOT EXISTS token_profile VARCHAR(20) NOT NULL DEFAULT 'LEGACY_GLOBAL';
COMMENT ON COLUMN iam_application_service.authorization_mode IS
    '授权模式:IAM_MANAGED / MAPPED / APP_MANAGED';
COMMENT ON COLUMN iam_application_service.token_profile IS
    '令牌收敛档:SCOPED 按应用裁剪 / LEGACY_GLOBAL 迁移期兼容;裁剪实现前保持 LEGACY_GLOBAL';
CREATE INDEX IF NOT EXISTS idx_iam_app_service_authorization
    ON iam_application_service(realm_id, authorization_mode) WHERE deleted = FALSE;

-- 2. 权限命名空间:平台级权限(应用为空)与应用级权限分别唯一
DROP INDEX IF EXISTS uk_iam_permission_code;
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_permission_platform_code
    ON iam_permission(realm_id, permission_code)
    WHERE deleted = FALSE AND app_service_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_permission_app_code
    ON iam_permission(realm_id, app_service_id, permission_code)
    WHERE deleted = FALSE AND app_service_id IS NOT NULL;

-- PostgreSQL 中 NULL 互不相等,现有 (realm_id, app_service_id, menu_code)
-- 无法约束平台菜单编码;平台菜单需要独立的部分唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_menu_platform_code
    ON iam_menu(realm_id, menu_code)
    WHERE deleted = FALSE AND app_service_id IS NULL;

-- 3. 角色应用归属(APP 容器角色必填,REALM/CLIENT 历史数据可为空)
ALTER TABLE iam_role ADD COLUMN IF NOT EXISTS app_service_id BIGINT;
COMMENT ON COLUMN iam_role.app_service_id IS
    '所属应用服务;APP 容器角色必填,REALM 角色为空,CLIENT 角色按绑定客户端回填';
DROP INDEX IF EXISTS uk_iam_role_scope;
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_role_scope
    ON iam_role(realm_id, container_type, COALESCE(app_service_id, 0),
                COALESCE(client_id, 0), role_code)
    WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_iam_role_app
    ON iam_role(realm_id, app_service_id) WHERE deleted = FALSE;

-- 4. 一个客户端只绑定一个应用,授权换票时才不会串应用
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_app_service_client
    ON iam_application_service(realm_id, sso_client_pk)
    WHERE deleted = FALSE AND sso_client_pk IS NOT NULL;

-- 5. 应用管理员与应用角色映射
CREATE TABLE IF NOT EXISTS iam_app_admin (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    app_service_id BIGINT NOT NULL,
    subject_type VARCHAR(10) NOT NULL,
    subject_id BIGINT NOT NULL,
    granted_by BIGINT,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_app_admin IS '应用管理员;subject_type:USER/GROUP;可维护本应用的菜单与授权';
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_app_admin
    ON iam_app_admin(app_service_id, subject_type, subject_id) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_iam_app_admin_subject
    ON iam_app_admin(subject_type, subject_id) WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS iam_app_role_mapping (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    app_service_id BIGINT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    source_id BIGINT NOT NULL,
    target_role_code VARCHAR(150) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_app_role_mapping IS 'IAM 组/域角色 → 应用角色映射;source_type:GROUP/REALM_ROLE';
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_app_role_mapping
    ON iam_app_role_mapping(app_service_id, source_type, source_id, target_role_code)
    WHERE deleted = FALSE;

-- 6. 存量 CLIENT 角色回填应用归属(一个客户端被多个应用绑定时由 10.2 检查拦截)
UPDATE iam_role r
SET app_service_id = x.app_id,
    updated_time = CURRENT_TIMESTAMP
FROM (
    SELECT sso_client_pk, MIN(id) AS app_id
    FROM iam_application_service
    WHERE deleted = FALSE AND sso_client_pk IS NOT NULL
    GROUP BY sso_client_pk
    HAVING COUNT(*) = 1
) x
WHERE r.deleted = FALSE
  AND r.container_type = 'CLIENT'
  AND r.app_service_id IS NULL
  AND r.client_id = x.sso_client_pk;

-- 7. 应用授权名单去重约束(重复授权会让整体覆盖语义失真)
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_app_service_grant
    ON iam_app_service_grant(app_service_id, subject_type, subject_id)
    WHERE deleted = FALSE;

-- 8. 权限点写操作权限码:管理端从"只读目录"升级为"可维护权限目录"
INSERT INTO iam_permission (realm_id, permission_code, permission_name, resource, action, description)
SELECT r.id, v.code, v.name, v.resource, v.action, v.description
FROM iam_realm r
         CROSS JOIN (VALUES
    ('permission:create', '新建权限', 'permission', 'create', '在平台或应用下新建权限点'),
    ('permission:update', '修改权限', 'permission', 'update', '修改权限名称/说明'),
    ('permission:delete', '删除权限', 'permission', 'delete', '删除未被角色引用的权限点')
) AS v(code, name, resource, action, description)
WHERE NOT EXISTS (SELECT 1 FROM iam_permission p
                  WHERE p.realm_id = r.id
                    AND p.permission_code = v.code
                    AND p.app_service_id IS NULL
                    AND NOT p.deleted);

-- 9. 内置超级管理员获得新增权限
INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
         JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
         JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE p.app_service_id IS NULL
  AND p.permission_code IN ('permission:create', 'permission:update', 'permission:delete')
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
