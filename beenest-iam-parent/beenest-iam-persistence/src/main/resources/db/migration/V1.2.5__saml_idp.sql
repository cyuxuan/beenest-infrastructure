-- ============================================================
-- V1.2.5 SAML 2.0 身份提供方(IdP)模式(P1-6)
-- 本迁移:
--   1. iam_saml_service_provider:域内登记的下游服务方(SP)。
--      一行对应一个 SP 实体,登记断言消费地址、NameID 格式、属性映射与验签证书。
--   2. 补种 saml-sp:view / saml-sp:update 权限码并授予 SUPER_ADMIN。
-- 语义:本平台作为 IdP 签发断言,SP 侧按元数据登记本平台;
--   与 iam_identity_provider(本平台作为 SP 对接外部 IdP)方向相反,两者互不影响。
-- ============================================================

CREATE TABLE IF NOT EXISTS iam_saml_service_provider (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    -- 站内别名:管理接口与日志用,不出现在 SAML 报文里
    sp_key VARCHAR(64) NOT NULL,
    -- SP 实体 ID:AuthnRequest 的 Issuer 与断言的 Audience 都按它匹配
    entity_id VARCHAR(512) NOT NULL,
    display_name VARCHAR(128) NOT NULL DEFAULT '',
    -- 断言消费地址:断言的 SubjectConfirmationData.Recipient 必须等于它
    acs_url VARCHAR(512) NOT NULL,
    -- 单点登出地址;为空表示该 SP 不支持 SLO
    slo_url VARCHAR(512) NOT NULL DEFAULT '',
    -- NameID 格式;SP 在 AuthnRequest 中指定时以 SP 为准
    name_id_format VARCHAR(128) NOT NULL DEFAULT 'urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified',
    -- 是否对断言签名(默认签;不签的 SP 拿不到可验证的断言,属自担风险)
    sign_assertion BOOLEAN NOT NULL DEFAULT TRUE,
    -- 是否强制要求 AuthnRequest 带签名;开启后未签名请求一律拒绝
    require_signed_authn_request BOOLEAN NOT NULL DEFAULT FALSE,
    -- SP 的签名证书(PEM),用于校验 AuthnRequest 签名;未强制验签时可为空
    sp_certificate TEXT NOT NULL DEFAULT '',
    -- 属性映射:键为断言中的属性名,值为取值表达式(username / email / attr.dept 等)
    attribute_mapping JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE iam_saml_service_provider IS 'SAML 2.0 服务方登记;本平台作为 IdP 按此签发断言';
COMMENT ON COLUMN iam_saml_service_provider.entity_id IS 'SP 实体 ID,同时用作断言的 AudienceRestriction';
COMMENT ON COLUMN iam_saml_service_provider.acs_url IS '断言消费地址,断言的 SubjectConfirmationData.Recipient 必须等于它';
COMMENT ON COLUMN iam_saml_service_provider.attribute_mapping IS '属性映射 JSONB:属性名 → 取值表达式';

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_saml_sp_entity
    ON iam_saml_service_provider(realm_id, entity_id) WHERE deleted = FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_saml_sp_key
    ON iam_saml_service_provider(realm_id, sp_key) WHERE deleted = FALSE;

-- 补种 SAML 服务方管理权限码(幂等)
INSERT INTO iam_permission(realm_id, permission_code, permission_name, resource, action)
SELECT r.id, v.code, v.name, v.resource, v.action
FROM iam_realm r
CROSS JOIN (
    VALUES
        ('saml-sp:view', '查看 SAML 服务方登记', 'saml-sp', 'view'),
        ('saml-sp:update', '管理 SAML 服务方登记', 'saml-sp', 'update')
) AS v(code, name, resource, action)
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_permission p WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE r.realm_key = 'default'
  AND p.permission_code IN ('saml-sp:view', 'saml-sp:update')
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
