-- 回滚 V1.1.3 安全加固
ALTER TABLE iam_client DROP COLUMN IF EXISTS grant_types;
ALTER TABLE iam_client DROP COLUMN IF EXISTS service_account_user_id;
DROP INDEX IF EXISTS idx_iam_client_service_account;

ALTER TABLE iam_login_policy DROP COLUMN IF EXISTS ip_max_fail_attempts;
ALTER TABLE iam_login_policy DROP COLUMN IF EXISTS ip_lock_duration_minutes;

DROP INDEX IF EXISTS idx_iam_user_session_state;
