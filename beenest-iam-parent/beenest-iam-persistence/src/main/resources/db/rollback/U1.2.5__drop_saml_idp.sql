-- 回滚 V1.2.5:移除 SAML 身份提供方(IdP)模式。
-- 回滚后已登记的 SP 全部丢失,下游应用无法再向本平台发起单点登录;
-- 本平台作为 SP 对接外部 IdP 的能力(iam_identity_provider)不受影响。

DELETE FROM iam_role_permission
WHERE permission_id IN (SELECT id FROM iam_permission
                        WHERE permission_code IN ('saml-sp:view', 'saml-sp:update'));
DELETE FROM iam_permission WHERE permission_code IN ('saml-sp:view', 'saml-sp:update');

DROP INDEX IF EXISTS uk_iam_saml_sp_key;
DROP INDEX IF EXISTS uk_iam_saml_sp_entity;
DROP TABLE IF EXISTS iam_saml_service_provider;
