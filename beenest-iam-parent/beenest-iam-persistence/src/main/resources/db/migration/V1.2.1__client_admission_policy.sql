-- ============================================================
-- 本迁移:客户端准入策略
--   1. iam_client 增加 allowed_login_types:客户端级第一因素白名单,
--      空串表示不限制(沿用域级可用方式)
--   2. iam_client 增加 require_mfa:客户端级强制二步验证开关,
--      与域级 iam_login_policy.force_mfa 叠加,任一为真即要求二步验证
-- 语义要点:
--   - 只加列不加约束:存量行落空串与 false,语义都是"不限制",
--     历史客户端升级后准入行为不变
--   - 客户端级强制可以晚于登录发生(用户先进门户,再进高敏应用),
--     因此会话侧需要记录 MFA 完成态,与本迁移无关
-- ============================================================

ALTER TABLE iam_client
    ADD COLUMN IF NOT EXISTS allowed_login_types VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE iam_client
    ADD COLUMN IF NOT EXISTS require_mfa BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN iam_client.allowed_login_types IS
    '客户端允许的第一因素集合(PASSWORD/LDAP/SMS/WECHAT/OIDC/SAML),逗号分隔;空 = 不限制';
COMMENT ON COLUMN iam_client.require_mfa IS
    '客户端强制 MFA;与域级 iam_login_policy.force_mfa 叠加,任一为真即要求二步验证';
