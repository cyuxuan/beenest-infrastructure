-- 回滚 V1.2.4:移除 Passkey 凭证表。
-- 回滚后已绑定的密钥记录全部丢失,用户需重新绑定;
-- 账号本身与其口令不受影响,仍可正常登录。

DROP INDEX IF EXISTS uk_iam_webauthn_credential_id;
DROP INDEX IF EXISTS idx_iam_webauthn_credential_user;
DROP INDEX IF EXISTS idx_iam_webauthn_credential_handle;
DROP TABLE IF EXISTS iam_webauthn_credential;
