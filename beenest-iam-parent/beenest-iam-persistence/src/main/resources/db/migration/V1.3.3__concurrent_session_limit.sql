-- ============================================================
-- V1.3.3 并发会话上限
--   1. iam_login_policy 增加 max_concurrent_sessions:同一账号同时在线
--      的会话数上限,0 表示不限制(与其它域级策略的 0 语义一致)。
--      超限时由新登录顶掉最旧会话,而不是拒绝新登录:拒绝会把
--      "旧设备其实已经不用了但会话还没到期"的用户锁在门外。
--   2. iam_user_session.state 增加 REPLACED:被新登录顶下线的会话
--      与超时失效分开记录,用户问"我为什么被踢了"时能直接回答。
-- ============================================================

ALTER TABLE iam_login_policy
    ADD COLUMN IF NOT EXISTS max_concurrent_sessions INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN iam_login_policy.max_concurrent_sessions IS '同一账号最大并发在线会话数;0 表示不限制,超限时新登录顶掉最旧会话';

COMMENT ON COLUMN iam_user_session.state IS '会话状态;ACTIVE 在线 / LOGGED_OUT 主动注销 / EXPIRED 超时失效 / REPLACED 被新登录顶下线';
