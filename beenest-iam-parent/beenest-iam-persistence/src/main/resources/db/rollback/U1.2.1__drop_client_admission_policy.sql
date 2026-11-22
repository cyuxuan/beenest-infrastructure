-- ============================================================
-- 回滚 V1.2.1(客户端准入策略)
-- 逆序执行:先删强制 MFA 列 → 删白名单列。
-- 注意:回滚后客户端回到"不限制、不强制 MFA"的状态,
--       与 V1.2.1 迁移前一致。
-- ============================================================

ALTER TABLE iam_client DROP COLUMN IF EXISTS require_mfa;
ALTER TABLE iam_client DROP COLUMN IF EXISTS allowed_login_types;
