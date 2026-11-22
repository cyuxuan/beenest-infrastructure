-- ============================================================
-- V1.1.7 租户级主题 见 V1.1.7__realm_theme.sql
-- 本迁移:短信验证码登录(P0-3)
--   1. iam_user 增加 phone_verified:只有已验证手机号可作第一因素,
--      未验证手机号不参与短信登录(避免脏数据成为认证通道)
--   2. 同域已验证手机号唯一:短信登录按手机号定位账号,重复会让认证歧义
--   3. iam_login_policy 增加短信登录开关与频率参数
-- ============================================================

ALTER TABLE iam_user
    ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN iam_user.phone_verified IS '手机号是否已验证;仅已验证手机号可用于短信验证码登录';

-- 同域内已验证手机号唯一;未验证手机号不占唯一约束(允许批量导入时的脏数据)
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_user_realm_phone_verified
    ON iam_user(realm_id, phone) WHERE deleted = FALSE AND phone_verified = TRUE AND phone IS NOT NULL;

-- 短信验证码登录:域级开关 + 发送频率(防短信轰炸)+ 校验尝试次数(防爆破)
ALTER TABLE iam_login_policy
    ADD COLUMN IF NOT EXISTS sms_login_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS sms_code_ttl_seconds INT NOT NULL DEFAULT 300,
    ADD COLUMN IF NOT EXISTS sms_max_send_per_hour INT NOT NULL DEFAULT 5,
    ADD COLUMN IF NOT EXISTS sms_max_verify_attempts INT NOT NULL DEFAULT 5;

COMMENT ON COLUMN iam_login_policy.sms_login_enabled IS '允许短信验证码登录(需另行配置短信通道)';
COMMENT ON COLUMN iam_login_policy.sms_code_ttl_seconds IS '短信验证码有效期(秒)';
COMMENT ON COLUMN iam_login_policy.sms_max_send_per_hour IS '同一手机号每小时最多发送条数,0 表示不限制';
COMMENT ON COLUMN iam_login_policy.sms_max_verify_attempts IS '同一验证码最多校验失败次数,超过即作废';
