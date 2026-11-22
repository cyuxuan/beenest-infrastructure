-- ============================================================
-- 种子数据(V1.0.1)
-- 1. default 身份域
-- 2. SUPER_ADMIN 系统角色并关联全部核心权限
-- 3. 核心权限集
-- 4. 默认客户端作用域
-- 5. 引导管理端客户端(公共客户端 + 强制 PKCE,不落任何密钥)
-- 幂等:全部使用 NOT EXISTS 守卫,可重复执行。
-- 管理员账号不在本文件创建,由启动时的 BootstrapAdminRunner
-- 读取环境变量 IAM_BOOTSTRAP_ADMIN_PASSWORD 完成。
-- ============================================================

-- 1. default 身份域
INSERT INTO iam_realm(realm_key, realm_name, enabled, is_default)
SELECT 'default', '默认域', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM iam_realm WHERE realm_key = 'default');

-- 2. 核心权限集
INSERT INTO iam_permission(realm_id, permission_code, permission_name, resource, action)
SELECT r.id, v.code, v.name, v.resource, v.action
FROM iam_realm r,
     (VALUES
        ('realm:view', '查看身份域', 'realm', 'view'),
        ('realm:create', '创建身份域', 'realm', 'create'),
        ('realm:update', '更新身份域', 'realm', 'update'),
        ('realm:delete', '删除身份域', 'realm', 'delete'),
        ('user:view', '查看用户', 'user', 'view'),
        ('user:create', '创建用户', 'user', 'create'),
        ('user:update', '更新用户', 'user', 'update'),
        ('user:delete', '删除用户', 'user', 'delete'),
        ('user:reset-password', '重置用户密码', 'user', 'reset-password'),
        ('user:assign-role', '分配用户角色', 'user', 'assign-role'),
        ('role:view', '查看角色', 'role', 'view'),
        ('role:create', '创建角色', 'role', 'create'),
        ('role:update', '更新角色', 'role', 'update'),
        ('role:delete', '删除角色', 'role', 'delete'),
        ('permission:view', '查看权限', 'permission', 'view'),
        ('menu:view', '查看菜单', 'menu', 'view'),
        ('client:view', '查看客户端', 'client', 'view'),
        ('client:create', '创建客户端', 'client', 'create'),
        ('client:update', '更新客户端', 'client', 'update'),
        ('client:delete', '删除客户端', 'client', 'delete'),
        ('idp:view', '查看身份提供方', 'idp', 'view'),
        ('app-service:view', '查看应用服务', 'app-service', 'view'),
        ('app-service:create', '创建应用服务', 'app-service', 'create'),
        ('app-service:update', '更新应用服务', 'app-service', 'update'),
        ('app-service:delete', '删除应用服务', 'app-service', 'delete'),
        ('app-service:grant', '授权应用服务', 'app-service', 'grant'),
        ('audit:view', '查看审计日志', 'audit', 'view'),
        ('attribute-def:view', '查看属性定义', 'attribute-def', 'view'),
        ('session:view', '查看会话', 'session', 'view'),
        ('session:revoke', '注销会话', 'session', 'revoke')
     ) AS v(code, name, resource, action)
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_permission p WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

-- 3. SUPER_ADMIN 系统角色
INSERT INTO iam_role(realm_id, role_code, role_name, container_type, is_system, is_active)
SELECT r.id, 'SUPER_ADMIN', '超级管理员', 'REALM', TRUE, TRUE
FROM iam_realm r
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_role ro WHERE ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted);

-- 4. SUPER_ADMIN 关联全部核心权限
INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);

-- 5. 默认客户端作用域
INSERT INTO iam_client_scope(realm_id, scope_key, scope_name, is_default)
SELECT r.id, v.key, v.name, TRUE
FROM iam_realm r,
     (VALUES
        ('openid', 'OpenID Connect 范围'),
        ('profile', '基础档案'),
        ('email', '邮箱'),
        ('launchpad', '应用门户')
     ) AS v(key, name)
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_client_scope cs
                  WHERE cs.realm_id = r.id AND cs.scope_key = v.key AND NOT cs.deleted);

-- 6. 引导管理端客户端(公共客户端 + 强制 PKCE,无密钥落库)
INSERT INTO iam_client(realm_id, client_key, client_name, client_type, client_secret,
                       redirect_uris, scopes, require_pkce, enabled, description)
SELECT r.id, 'iam-admin-web', '管理后台(Web)', 'PUBLIC', NULL,
       'http://localhost:5173/callback', 'openid,profile,email,launchpad', TRUE, TRUE,
       '管理后台引导客户端,Phase 3 Vue 端使用'
FROM iam_realm r
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_client c
                  WHERE c.realm_id = r.id AND c.client_key = 'iam-admin-web' AND NOT c.deleted);
