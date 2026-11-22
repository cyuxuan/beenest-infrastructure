-- 回滚 V1.3.3:移除并发会话上限与 REPLACED 状态说明。
-- 已置为 REPLACED 的会话行保留原值:回滚不应改写历史会话记录的状态。

COMMENT ON COLUMN iam_user_session.state IS '会话状态;ACTIVE/LOGGED_OUT/EXPIRED';

ALTER TABLE iam_login_policy DROP COLUMN IF EXISTS max_concurrent_sessions;
