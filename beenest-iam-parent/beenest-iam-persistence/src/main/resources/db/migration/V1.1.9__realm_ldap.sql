-- ============================================================
-- V1.1.8 短信登录 见 V1.1.8__sms_login.sql
-- 本迁移:LDAP/AD 用户联合(P0-4)
--   1. iam_realm_ldap_config:每域一条 LDAP 目录配置(绑定账号、用户搜索基、
--      过滤器与属性映射);bind 口令信封加密落库(enc:v1: 前缀),管理接口只写不读
--   2. 补种 ldap-config:view / ldap-config:update 权限码并授予 SUPER_ADMIN
-- 语义:本地账号优先,本地不存在时到 LDAP bind 认证(AD 口令校验的唯一可靠方式),
--   成功后按需创建本地用户(JIT),后续登录复用本地账号;LDAP 失败对外不可区分
-- ============================================================

CREATE TABLE IF NOT EXISTS iam_realm_ldap_config (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    -- 目录服务地址:ldap://host:389 或 ldaps://host:636
    server_url VARCHAR(255) NOT NULL DEFAULT '',
    -- 搜索用绑定账号(只读权限即可);为空时以匿名绑定搜索(目录允许时)
    bind_dn VARCHAR(255) NOT NULL DEFAULT '',
    -- bind 口令密文:IamSecretCrypto(purpose=iam-ldap-bind-password, scope=realmId) 信封加密
    bind_password VARCHAR(512) NOT NULL DEFAULT '',
    -- 用户搜索基 DN,如 OU=Employees,DC=corp,DC=local
    user_base_dn VARCHAR(512) NOT NULL DEFAULT '',
    -- 用户定位过滤器,{0} 为登录名占位(转义后填入),如 (sAMAccountName={0})
    user_filter VARCHAR(255) NOT NULL DEFAULT '(sAMAccountName={0})',
    -- 属性映射:AD/OpenLDAP 的属性名在各目录中不同,全部可配
    uuid_attribute VARCHAR(64) NOT NULL DEFAULT 'objectGUID',
    username_attribute VARCHAR(64) NOT NULL DEFAULT 'sAMAccountName',
    email_attribute VARCHAR(64) NOT NULL DEFAULT 'mail',
    display_name_attribute VARCHAR(64) NOT NULL DEFAULT 'displayName',
    -- JNDI 连接与读取超时(毫秒):目录不可达时登录链路不能无限等待
    connect_timeout_ms INT NOT NULL DEFAULT 3000,
    read_timeout_ms INT NOT NULL DEFAULT 5000,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE iam_realm_ldap_config IS '域级 LDAP/AD 用户联合配置;本地账号优先,LDAP 仅作兜底认证源并 JIT 建号';
COMMENT ON COLUMN iam_realm_ldap_config.bind_password IS 'bind 口令密文(IamSecretCrypto 信封加密);管理接口只写不读,留空表示不修改';
COMMENT ON COLUMN iam_realm_ldap_config.user_filter IS '用户定位过滤器,{0} 为登录名占位;登录名按 RFC4515 转义后填入,防 LDAP 注入';

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_realm_ldap_config_realm
    ON iam_realm_ldap_config(realm_id) WHERE deleted = FALSE;

-- 补种 LDAP 配置管理权限码(幂等)
INSERT INTO iam_permission(realm_id, permission_code, permission_name, resource, action)
SELECT r.id, v.code, v.name, v.resource, v.action
FROM iam_realm r
CROSS JOIN (
    VALUES
        ('ldap-config:view', '查看 LDAP 用户联合配置', 'ldap-config', 'view'),
        ('ldap-config:update', '管理 LDAP 用户联合配置', 'ldap-config', 'update')
) AS v(code, name, resource, action)
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_permission p WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE r.realm_key = 'default'
  AND p.permission_code IN ('ldap-config:view', 'ldap-config:update')
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
