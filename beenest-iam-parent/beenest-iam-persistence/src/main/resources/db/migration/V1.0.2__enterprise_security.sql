-- ============================================================
-- beenest-iam 企业级能力迁移(V1.0.2)
-- 1. iam_group_role           组-角色关联(组作为权限主体)
-- 2. iam_password_policy      域级口令策略(复杂度/历史/有效期)
-- 3. iam_login_policy         域级登录策略(锁定阈值/验证码/强制 MFA)
-- 4. iam_user_password_history 口令历史(防重复使用,仅追加)
-- 5. 补种企业级权限码(组/属性定义/协议映射器/客户端作用域/IdP/安全策略)
-- 幂等:DDL 使用 IF NOT EXISTS,数据使用 NOT EXISTS 守卫,可重复执行。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 组-角色关联:用户→组→角色,组内成员继承组角色
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam_group_role (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    granted_by BIGINT,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_group_role IS '组-角色关联;组内成员通过组继承角色权限';
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_group_role ON iam_group_role(group_id, role_id) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_iam_group_role_role ON iam_group_role(role_id) WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 2. 域级口令策略(每域一行,缺省值即保守基线)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam_password_policy (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    min_length INT NOT NULL DEFAULT 8,
    max_length INT NOT NULL DEFAULT 64,
    require_uppercase BOOLEAN NOT NULL DEFAULT FALSE,
    require_lowercase BOOLEAN NOT NULL DEFAULT FALSE,
    require_digit BOOLEAN NOT NULL DEFAULT TRUE,
    require_symbol BOOLEAN NOT NULL DEFAULT FALSE,
    history_count INT NOT NULL DEFAULT 0,
    expire_days INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_password_policy IS '域级口令策略;history_count 禁止重复使用最近 N 个口令,expire_days 为有效期(0 永不过期)';
COMMENT ON COLUMN iam_password_policy.require_digit IS '国内常见合规基线:默认要求数字';
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_password_policy_realm ON iam_password_policy(realm_id) WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 3. 域级登录策略(每域一行)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam_login_policy (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    max_fail_attempts INT NOT NULL DEFAULT 5,
    failure_window_minutes INT NOT NULL DEFAULT 15,
    lock_duration_minutes INT NOT NULL DEFAULT 15,
    captcha_threshold INT NOT NULL DEFAULT 3,
    force_mfa BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_login_policy IS '域级登录策略;captcha_threshold 失败达 N 次后要求验证码(0 关闭),force_mfa 强制全员启用 MFA';
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_login_policy_realm ON iam_login_policy(realm_id) WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 4. 口令历史:仅追加,保留最近 N 条由策略驱动,清理在应用层进行
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam_user_password_history (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    password_hash TEXT NOT NULL,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_user_password_history IS '口令历史(仅追加);与 iam_password_policy.history_count 配合防重复使用';
CREATE INDEX IF NOT EXISTS idx_iam_pwd_history_user ON iam_user_password_history(user_id, id DESC);

-- ------------------------------------------------------------
-- 5. 补种企业级权限码(幂等)
-- ------------------------------------------------------------
INSERT INTO iam_permission(realm_id, permission_code, permission_name, resource, action)
SELECT r.id, v.code, v.name, v.resource, v.action
FROM iam_realm r,
     (VALUES
        ('group:view', '查看用户组', 'group', 'view'),
        ('group:create', '创建用户组', 'group', 'create'),
        ('group:update', '更新用户组', 'group', 'update'),
        ('group:delete', '删除用户组', 'group', 'delete'),
        ('group:assign-user', '管理组成员', 'group', 'assign-user'),
        ('group:assign-role', '分配组角色', 'group', 'assign-role'),
        ('attribute-def:view', '查看属性定义', 'attribute-def', 'view'),
        ('attribute-def:create', '创建属性定义', 'attribute-def', 'create'),
        ('attribute-def:update', '更新属性定义', 'attribute-def', 'update'),
        ('attribute-def:delete', '删除属性定义', 'attribute-def', 'delete'),
        ('protocol-mapper:view', '查看协议映射器', 'protocol-mapper', 'view'),
        ('protocol-mapper:create', '创建协议映射器', 'protocol-mapper', 'create'),
        ('protocol-mapper:update', '更新协议映射器', 'protocol-mapper', 'update'),
        ('protocol-mapper:delete', '删除协议映射器', 'protocol-mapper', 'delete'),
        ('client-scope:view', '查看客户端作用域', 'client-scope', 'view'),
        ('client-scope:create', '创建客户端作用域', 'client-scope', 'create'),
        ('client-scope:update', '更新客户端作用域', 'client-scope', 'update'),
        ('client-scope:delete', '删除客户端作用域', 'client-scope', 'delete'),
        ('idp:create', '创建身份提供方', 'idp', 'create'),
        ('idp:update', '更新身份提供方', 'idp', 'update'),
        ('idp:delete', '删除身份提供方', 'idp', 'delete'),
        ('idp-mapper:view', '查看 IdP 属性映射器', 'idp-mapper', 'view'),
        ('idp-mapper:create', '创建 IdP 属性映射器', 'idp-mapper', 'create'),
        ('idp-mapper:update', '更新 IdP 属性映射器', 'idp-mapper', 'update'),
        ('idp-mapper:delete', '删除 IdP 属性映射器', 'idp-mapper', 'delete'),
        ('security-policy:view', '查看安全策略', 'security-policy', 'view'),
        ('security-policy:update', '更新安全策略', 'security-policy', 'update'),
        ('mfa:view', '查看 MFA 状态', 'mfa', 'view'),
        ('mfa:update', '管理 MFA', 'mfa', 'update')
     ) AS v(code, name, resource, action)
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_permission p WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

-- 6. SUPER_ADMIN 自动获得补种权限(幂等)
INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);

-- 7. default 域初始化口令/登录策略(幂等)
INSERT INTO iam_password_policy(realm_id)
SELECT r.id FROM iam_realm r
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_password_policy pp WHERE pp.realm_id = r.id AND NOT pp.deleted);

INSERT INTO iam_login_policy(realm_id)
SELECT r.id FROM iam_realm r
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_login_policy lp WHERE lp.realm_id = r.id AND NOT lp.deleted);
