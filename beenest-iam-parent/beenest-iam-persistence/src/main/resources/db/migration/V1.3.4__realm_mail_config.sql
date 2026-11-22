-- ============================================================
-- V1.3.4 域级发信配置与邮件模板覆盖
--   1. iam_realm_mail_config:每域一条 SMTP 配置。多租户部署下各租户
--      往往有自己的发信服务器与发件人,全局 iam.mail.* 只应作为兜底。
--   2. iam_mail_template:模板按域覆盖。内置模板是 classpath 资源,
--      租户要改品牌与措辞时不能靠改代码重新打包。
--   3. 补种 mail-config:view / mail-config:update 并授予 SUPER_ADMIN。
-- 按域逐个补种(与 V1.1.5 同一口径):每个域的管理员都要能维护
--   自己的发信配置,只种默认域会让其它域永远无法自助配置。
-- ============================================================

CREATE TABLE IF NOT EXISTS iam_realm_mail_config (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    host VARCHAR(255) NOT NULL DEFAULT '',
    port INT NOT NULL DEFAULT 587,
    username VARCHAR(255),
    -- SMTP 口令密文:IamSecretCrypto(purpose=iam-realm-mail-password, scope=realmId) 信封加密
    password VARCHAR(1024),
    from_address VARCHAR(255),
    from_name VARCHAR(128) NOT NULL DEFAULT '统一身份认证',
    starttls BOOLEAN NOT NULL DEFAULT TRUE,
    ssl BOOLEAN NOT NULL DEFAULT FALSE,
    timeout_millis INT NOT NULL DEFAULT 10000,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE iam_realm_mail_config IS '域级发信配置;启用且配置完整时本域邮件经该 SMTP 发出,否则回落到全局 iam.mail.*';
COMMENT ON COLUMN iam_realm_mail_config.password IS 'SMTP 口令密文(IamSecretCrypto 信封加密);管理接口只写不读,留空表示不修改';

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_realm_mail_config_realm
    ON iam_realm_mail_config(realm_id) WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS iam_mail_template (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    template_code VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE iam_mail_template IS '域级邮件模板覆盖;无覆盖记录时使用 classpath 内置模板,逻辑删除即回落';

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_mail_template_realm_code
    ON iam_mail_template(realm_id, template_code) WHERE deleted = FALSE;

INSERT INTO iam_permission (realm_id, permission_code, permission_name, resource, action, description)
SELECT r.id, v.code, v.name, v.resource, v.action, v.description
FROM iam_realm r
         CROSS JOIN (VALUES
    ('mail-config:view', '查看发信配置', 'mail-config', 'view',
     '查看本域 SMTP 配置与邮件模板覆盖状态'),
    ('mail-config:update', '管理发信配置', 'mail-config', 'update',
     '维护本域 SMTP 配置、发送测试邮件与编辑邮件模板')
) AS v(code, name, resource, action, description)
WHERE NOT EXISTS (SELECT 1 FROM iam_permission p
                  WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

INSERT INTO iam_role_permission (realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
         JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
         JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE p.permission_code IN ('mail-config:view', 'mail-config:update')
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
