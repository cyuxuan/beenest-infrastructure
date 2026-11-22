-- 回滚 V1.1.8:撤销短信验证码登录。
-- 删除后短信登录开关与频率参数一并消失,登录策略退回"仅口令/LDAP/Passkey"等既有方式;
-- phone_verified 列删除后,已完成的手机号验证状态丢失,重新升级需重新验证。

-- 短信登录参数
ALTER TABLE iam_login_policy DROP COLUMN IF EXISTS sms_max_verify_attempts;
ALTER TABLE iam_login_policy DROP COLUMN IF EXISTS sms_max_send_per_hour;
ALTER TABLE iam_login_policy DROP COLUMN IF EXISTS sms_code_ttl_seconds;
ALTER TABLE iam_login_policy DROP COLUMN IF EXISTS sms_login_enabled;

-- 已验证手机号唯一约束
DROP INDEX IF EXISTS uk_iam_user_realm_phone_verified;

-- 手机号验证标记
ALTER TABLE iam_user DROP COLUMN IF EXISTS phone_verified;
