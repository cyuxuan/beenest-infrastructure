-- ============================================================
-- beenest-iam 初始化 Schema(V1.0.0)
-- 全部表带 created_time/updated_time/deleted 软删除;
-- 唯一约束一律使用部分唯一索引(WHERE deleted = FALSE);
-- 禁止外键约束,关联在应用层维护。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 身份域(租户)
-- ------------------------------------------------------------
CREATE TABLE iam_realm (
    id BIGSERIAL PRIMARY KEY,
    realm_key VARCHAR(64) NOT NULL,
    realm_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    access_token_ttl BIGINT NOT NULL DEFAULT 300,
    refresh_token_ttl BIGINT NOT NULL DEFAULT 1800,
    sso_session_idle BIGINT NOT NULL DEFAULT 1800,
    sso_session_max BIGINT NOT NULL DEFAULT 36000,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_realm IS '身份域(租户),每个域独立 issuer 与签名密钥';
COMMENT ON COLUMN iam_realm.realm_key IS '域标识(issuer 路径段,如 default)';
CREATE UNIQUE INDEX uk_iam_realm_realm_key ON iam_realm(realm_key) WHERE deleted = FALSE;

-- 域签名密钥(每域独立 RSA 密钥对,私钥加密存储)
CREATE TABLE iam_realm_key (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    key_type VARCHAR(20) NOT NULL DEFAULT 'RSA',
    private_key TEXT NOT NULL,
    public_key TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    activated_time TIMESTAMP,
    retired_time TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_realm_key IS '域签名密钥,支持轮换(新密钥行 active,旧密钥保留供验签)';
CREATE INDEX idx_iam_realm_key_realm ON iam_realm_key(realm_id) WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 2. 用户与凭证
-- ------------------------------------------------------------
CREATE TABLE iam_user (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    username VARCHAR(100) NOT NULL,
    nickname VARCHAR(100),
    email VARCHAR(200),
    phone VARCHAR(20),
    status SMALLINT NOT NULL DEFAULT 1,
    user_type VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    token_version INT NOT NULL DEFAULT 0,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_user IS '用户;attributes 为扩展属性真值源,token_version 自增即令该用户全部已签发令牌失效';
COMMENT ON COLUMN iam_user.status IS '状态:1正常 0禁用 2锁定';
COMMENT ON COLUMN iam_user.user_type IS '用户类型:INTERNAL内部 EXTERNAL外部 SERVICE服务';
CREATE UNIQUE INDEX uk_iam_user_realm_username ON iam_user(realm_id, username) WHERE deleted = FALSE;
CREATE INDEX idx_iam_user_realm_email ON iam_user(realm_id, email) WHERE deleted = FALSE;
CREATE INDEX idx_iam_user_attributes_gin ON iam_user USING GIN (attributes jsonb_path_ops);

-- 用户凭证(密码/OTP/WebAuthn/恢复码分类型存放)
CREATE TABLE iam_user_credential (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    credential_type VARCHAR(30) NOT NULL,
    secret_data TEXT NOT NULL,
    salt TEXT,
    algorithm VARCHAR(50) NOT NULL DEFAULT 'bcrypt',
    iterations INT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    expired_time TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_user_credential IS '用户凭证;credential_type:PASSWORD/OTP/WEBAUTHN/RECOVERY_CODE';
CREATE INDEX idx_iam_user_credential_user ON iam_user_credential(user_id, credential_type) WHERE deleted = FALSE;

-- 属性定义(声明式 Schema,驱动校验与后台属性构建器)
CREATE TABLE iam_attribute_def (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    attr_key VARCHAR(100) NOT NULL,
    attr_name VARCHAR(100) NOT NULL,
    attr_type VARCHAR(20) NOT NULL DEFAULT 'STRING',
    required BOOLEAN NOT NULL DEFAULT FALSE,
    searchable BOOLEAN NOT NULL DEFAULT FALSE,
    unique_flag BOOLEAN NOT NULL DEFAULT FALSE,
    enum_options TEXT,
    validation_regex VARCHAR(500),
    description VARCHAR(500),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_attribute_def IS '用户属性定义;attr_type:STRING/NUMBER/BOOLEAN/DATE/ENUM/JSON';
CREATE UNIQUE INDEX uk_iam_attribute_def_key ON iam_attribute_def(realm_id, attr_key) WHERE deleted = FALSE;

-- 用户属性索引投影(仅 searchable/unique 属性写入,提供强唯一约束)
CREATE TABLE iam_user_attribute_index (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    attr_key VARCHAR(100) NOT NULL,
    attr_value TEXT NOT NULL,
    is_unique BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_user_attribute_index IS '用户可搜索/唯一属性投影;与 iam_user.attributes 同事务维护';
CREATE INDEX idx_iam_user_attr_idx ON iam_user_attribute_index(realm_id, attr_key, attr_value) WHERE deleted = FALSE;
COMMENT ON COLUMN iam_user_attribute_index.is_unique IS '冗余自属性定义的唯一标记;is_unique 行参与全局唯一约束';
CREATE UNIQUE INDEX uk_iam_user_attr_unique ON iam_user_attribute_index(realm_id, attr_key, attr_value) WHERE deleted = FALSE AND is_unique;

-- ------------------------------------------------------------
-- 3. 角色与主体
-- ------------------------------------------------------------
CREATE TABLE iam_role (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    role_code VARCHAR(100) NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    container_type VARCHAR(20) NOT NULL DEFAULT 'REALM',
    client_id BIGINT,
    is_system BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_role IS '角色;container_type 为 REALM(域角色)或 CLIENT(客户端角色)';
CREATE UNIQUE INDEX uk_iam_role_scope ON iam_role(realm_id, container_type, COALESCE(client_id, 0), role_code) WHERE deleted = FALSE;

CREATE TABLE iam_user_role (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    granted_by BIGINT,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_user_role IS '用户-角色关联';
CREATE INDEX idx_iam_user_role_user ON iam_user_role(user_id) WHERE deleted = FALSE;
CREATE INDEX idx_iam_user_role_role ON iam_user_role(role_id) WHERE deleted = FALSE;
CREATE UNIQUE INDEX uk_iam_user_role ON iam_user_role(user_id, role_id) WHERE deleted = FALSE;

CREATE TABLE iam_group (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    parent_id BIGINT,
    group_code VARCHAR(100) NOT NULL,
    group_name VARCHAR(100) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_group IS '用户组(树形,parent_id 为空表示根)';
CREATE UNIQUE INDEX uk_iam_group_code ON iam_group(realm_id, group_code) WHERE deleted = FALSE;

CREATE TABLE iam_user_group (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_user_group IS '用户-组关联';
CREATE UNIQUE INDEX uk_iam_user_group ON iam_user_group(user_id, group_id) WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 4. 应用与协议
-- ------------------------------------------------------------
CREATE TABLE iam_client (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    client_key VARCHAR(100) NOT NULL,
    client_name VARCHAR(200) NOT NULL,
    client_type VARCHAR(20) NOT NULL DEFAULT 'CONFIDENTIAL',
    client_secret TEXT,
    redirect_uris TEXT NOT NULL,
    scopes TEXT NOT NULL DEFAULT 'openid,profile',
    require_pkce BOOLEAN NOT NULL DEFAULT TRUE,
    access_token_ttl BIGINT,
    refresh_token_ttl BIGINT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_client IS '接入客户端(应用);client_type:PUBLIC 公共客户端强制 PKCE / CONFIDENTIAL 机密客户端';
COMMENT ON COLUMN iam_client.redirect_uris IS '回调地址列表,逗号分隔';
CREATE UNIQUE INDEX uk_iam_client_key ON iam_client(realm_id, client_key) WHERE deleted = FALSE;

CREATE TABLE iam_client_scope (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    scope_key VARCHAR(100) NOT NULL,
    scope_name VARCHAR(100) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_client_scope IS '客户端作用域';
CREATE UNIQUE INDEX uk_iam_client_scope_key ON iam_client_scope(realm_id, scope_key) WHERE deleted = FALSE;

CREATE TABLE iam_client_scope_mapping (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    client_id BIGINT NOT NULL,
    scope_id BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_client_scope_mapping IS '客户端-作用域关联';
CREATE UNIQUE INDEX uk_iam_client_scope_mapping ON iam_client_scope_mapping(client_id, scope_id) WHERE deleted = FALSE;

CREATE TABLE iam_protocol_mapper (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    client_id BIGINT,
    name VARCHAR(100) NOT NULL,
    claim_name VARCHAR(100) NOT NULL,
    claim_type VARCHAR(30) NOT NULL DEFAULT 'STRING',
    user_attribute VARCHAR(100),
    claim_template VARCHAR(500),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_protocol_mapper IS '协议映射器;将用户属性/固定值映射为令牌 claim,claim_type:STRING/NUMBER/BOOLEAN/JSON';
CREATE INDEX idx_iam_protocol_mapper_client ON iam_protocol_mapper(realm_id, client_id) WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 5. 组织 RBAC(菜单/权限)
-- ------------------------------------------------------------
CREATE TABLE iam_permission (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    permission_code VARCHAR(150) NOT NULL,
    permission_name VARCHAR(200) NOT NULL,
    resource VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    description VARCHAR(500),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_permission IS '权限;permission_code 格式为 resource:action';
CREATE UNIQUE INDEX uk_iam_permission_code ON iam_permission(realm_id, permission_code) WHERE deleted = FALSE;

CREATE TABLE iam_menu (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    parent_id BIGINT,
    menu_code VARCHAR(100) NOT NULL,
    menu_name VARCHAR(100) NOT NULL,
    menu_type VARCHAR(20) NOT NULL DEFAULT 'MENU',
    path VARCHAR(300),
    icon VARCHAR(100),
    permission_id BIGINT,
    sort_order INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_menu IS '菜单(展示项,树形);menu_type:MENU 目录/页面 / BUTTON 按钮;挂载 permission_id 的菜单项随角色授予派生权限';
COMMENT ON COLUMN iam_menu.permission_id IS '菜单关联的权限;为空表示纯展示目录';
CREATE UNIQUE INDEX uk_iam_menu_code ON iam_menu(realm_id, menu_code) WHERE deleted = FALSE;

CREATE TABLE iam_role_menu (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_role_menu IS '角色-菜单关联;授予菜单即同时派生其下全部权限';
CREATE UNIQUE INDEX uk_iam_role_menu ON iam_role_menu(role_id, menu_id) WHERE deleted = FALSE;

CREATE TABLE iam_role_permission (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_role_permission IS '角色-权限直接关联(菜单派生之外的部分)';
CREATE UNIQUE INDEX uk_iam_role_permission ON iam_role_permission(role_id, permission_id) WHERE deleted = FALSE;

CREATE TABLE iam_user_permission (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    grant_type VARCHAR(10) NOT NULL,
    reason VARCHAR(500),
    expired_time TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_user_permission IS '用户级特殊授权/撤销;grant_type:GRANT 临时授予 / REVOKE 临时撤销,expired_time 到期自动失效';
CREATE INDEX idx_iam_user_permission_user ON iam_user_permission(user_id) WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 6. 应用服务目录(应用门户/启动台)
-- ------------------------------------------------------------
CREATE TABLE iam_application_service (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    app_code VARCHAR(100) NOT NULL,
    app_name VARCHAR(200) NOT NULL,
    app_type VARCHAR(30) NOT NULL DEFAULT 'WEB',
    sso_mode VARCHAR(20) NOT NULL DEFAULT 'OIDC',
    sso_client_pk BIGINT,
    icon_url VARCHAR(500),
    entry_url VARCHAR(500),
    category VARCHAR(50),
    sort_order INT NOT NULL DEFAULT 0,
    visibility VARCHAR(20) NOT NULL DEFAULT 'PERMISSION',
    required_permission_code VARCHAR(150),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_application_service IS '应用服务目录,驱动登录后的应用门户;visibility:PUBLIC 全员可见 / ROLE 按角色 / PERMISSION 按权限';
CREATE UNIQUE INDEX uk_iam_app_service_code ON iam_application_service(realm_id, app_code) WHERE deleted = FALSE;

CREATE TABLE iam_app_service_grant (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    app_service_id BIGINT NOT NULL,
    subject_type VARCHAR(10) NOT NULL,
    subject_id BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_app_service_grant IS '应用服务授权;subject_type:USER/ROLE/GROUP,visibility 为 ROLE 时生效';
CREATE INDEX idx_iam_app_service_grant_app ON iam_app_service_grant(app_service_id) WHERE deleted = FALSE;
CREATE INDEX idx_iam_app_service_grant_subject ON iam_app_service_grant(subject_type, subject_id) WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 7. 会话与授权记录
-- ------------------------------------------------------------
CREATE TABLE iam_user_session (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    sid VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    login_type VARCHAR(20) NOT NULL DEFAULT 'PASSWORD',
    ip VARCHAR(64),
    user_agent VARCHAR(500),
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_active_time TIMESTAMP,
    expired_time TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_user_session IS '用户会话记录(供审计/会话管理列表);state:ACTIVE/LOGGED_OUT/EXPIRED';
CREATE UNIQUE INDEX uk_iam_user_session_sid ON iam_user_session(sid) WHERE deleted = FALSE;
CREATE INDEX idx_iam_user_session_user ON iam_user_session(realm_id, user_id) WHERE deleted = FALSE;

CREATE TABLE iam_client_session (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    user_session_id BIGINT NOT NULL,
    client_id BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_client_session IS '客户端会话(用户会话内按客户端记录)';
CREATE INDEX idx_iam_client_session_user_session ON iam_client_session(user_session_id) WHERE deleted = FALSE;

CREATE TABLE iam_consent (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    principal_id BIGINT NOT NULL,
    client_id BIGINT NOT NULL,
    scopes TEXT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_consent IS '用户对客户端的授权同意记录;principal_id 为用户主键';
CREATE UNIQUE INDEX uk_iam_consent ON iam_consent(principal_id, client_id) WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 8. 联邦身份(P1 建表稳定 Schema,P2 启用)
-- ------------------------------------------------------------
CREATE TABLE iam_identity_provider (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    alias VARCHAR(100) NOT NULL,
    provider_id VARCHAR(50) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_identity_provider IS '身份提供方配置;provider_id 为类型(oidc/wechat/alipay/feishu),alias 为域内唯一别名';
CREATE UNIQUE INDEX uk_iam_idp_alias ON iam_identity_provider(realm_id, alias) WHERE deleted = FALSE;

CREATE TABLE iam_identity_provider_mapper (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    idp_id BIGINT NOT NULL,
    mapper_type VARCHAR(30) NOT NULL,
    source_attribute VARCHAR(100),
    target_attribute VARCHAR(100),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_identity_provider_mapper IS '身份提供方属性映射器(外部属性 → 本地用户属性)';
CREATE INDEX idx_iam_idp_mapper_idp ON iam_identity_provider_mapper(idp_id) WHERE deleted = FALSE;

CREATE TABLE iam_user_federated_identity (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    idp_id BIGINT NOT NULL,
    external_id VARCHAR(200) NOT NULL,
    external_username VARCHAR(200),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_user_federated_identity IS '用户-外部身份绑定;external_id 为 IdP 侧 subject';
CREATE UNIQUE INDEX uk_iam_user_federated ON iam_user_federated_identity(realm_id, idp_id, external_id) WHERE deleted = FALSE;
CREATE UNIQUE INDEX uk_iam_user_federated_user ON iam_user_federated_identity(user_id, idp_id) WHERE deleted = FALSE;

-- ------------------------------------------------------------
-- 9. 审计日志(仅追加,不可变)
-- ------------------------------------------------------------
CREATE TABLE iam_audit_log (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT,
    operator_id BIGINT,
    operator_name VARCHAR(100),
    action VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    target_type VARCHAR(50),
    target_id BIGINT,
    ip VARCHAR(64),
    occurred_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_audit_log IS '审计日志,仅插入不允许修改删除';
CREATE INDEX idx_iam_audit_log_realm_time ON iam_audit_log(realm_id, occurred_time);
CREATE INDEX idx_iam_audit_log_action ON iam_audit_log(action);
