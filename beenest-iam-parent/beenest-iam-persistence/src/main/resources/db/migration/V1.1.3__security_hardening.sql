-- =====================================================================
-- V1.1.3 安全加固:客户端授权类型/服务账号、登录 IP 限流、会话状态索引
-- ---------------------------------------------------------------------
-- 背景:
--   1) 客户端此前只能在授权码 + 刷新令牌两种授权类型间二选一(写死在代码里),
--      机器对机器(client_credentials)无法接入,管理 API 也没有 Bearer 通道;
--   2) 登录失败计数只按"域 + 登录名"维度,密码喷洒(同一 IP 遍历多个账号)
--      完全不受约束;
--   3) 会话记录在用户异常退出后永久停留 ACTIVE,"在线会话"列表失真,
--      缺少按状态检索的索引。
-- 本脚本补齐上述三项的落库结构,均为向后兼容的追加式变更。
-- =====================================================================

-- ------------------------------------------------------------
-- 1. 客户端:授权类型与服务账号主体
-- ------------------------------------------------------------
ALTER TABLE iam_client ADD COLUMN IF NOT EXISTS grant_types TEXT NOT NULL DEFAULT 'authorization_code,refresh_token';
COMMENT ON COLUMN iam_client.grant_types IS '允许的授权类型,逗号分隔:authorization_code / refresh_token / client_credentials';

ALTER TABLE iam_client ADD COLUMN IF NOT EXISTS service_account_user_id BIGINT;
COMMENT ON COLUMN iam_client.service_account_user_id IS 'client_credentials 的主体用户(服务账号);为空表示该客户端不允许凭据模式换取令牌';

CREATE INDEX IF NOT EXISTS idx_iam_client_service_account ON iam_client(service_account_user_id) WHERE deleted = FALSE AND service_account_user_id IS NOT NULL;

-- ------------------------------------------------------------
-- 2. 登录策略:IP 维度限流
-- ------------------------------------------------------------
ALTER TABLE iam_login_policy ADD COLUMN IF NOT EXISTS ip_max_fail_attempts INT NOT NULL DEFAULT 20;
ALTER TABLE iam_login_policy ADD COLUMN IF NOT EXISTS ip_lock_duration_minutes INT NOT NULL DEFAULT 15;
COMMENT ON COLUMN iam_login_policy.ip_max_fail_attempts IS '同一来源 IP 在窗口期内的最大失败次数;0 表示不按 IP 限制';
COMMENT ON COLUMN iam_login_policy.ip_lock_duration_minutes IS 'IP 维度锁定时长(分钟)';

-- ------------------------------------------------------------
-- 3. 会话记录:按状态检索索引(在线会话列表只取 ACTIVE)
-- ------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_iam_user_session_state ON iam_user_session(realm_id, state) WHERE deleted = FALSE;
