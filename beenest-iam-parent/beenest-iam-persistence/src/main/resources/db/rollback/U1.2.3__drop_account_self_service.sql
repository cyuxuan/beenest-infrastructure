-- 回滚 V1.2.3:账户自助服务
DROP INDEX IF EXISTS idx_iam_notification_log_recipient;
DROP INDEX IF EXISTS idx_iam_notification_log_realm_time;
DROP TABLE IF EXISTS iam_notification_log;
ALTER TABLE iam_user DROP COLUMN IF EXISTS required_actions;
