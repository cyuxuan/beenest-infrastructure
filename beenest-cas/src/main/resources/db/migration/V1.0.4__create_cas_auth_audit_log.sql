-- 认证审计日志表
-- 记录所有认证事件（成功/失败/锁定），供安全追溯和风控分析
CREATE TABLE IF NOT EXISTS cas_auth_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(32),                          -- 目标用户（失败时可能为空）
    principal       VARCHAR(128),                         -- 登录标识（手机号/用户名/openid 等）
    auth_type       VARCHAR(30) NOT NULL,                 -- 认证方式: WECHAT/DOUYIN_MINI/ALIPAY_MINI/PHONE_SMS/PASSWORD/APP_REFRESH
    auth_result     VARCHAR(20) NOT NULL,                 -- 结果: SUCCESS/FAILED/LOCKED/DISABLED
    failure_reason  VARCHAR(256),                         -- 失败原因
    client_ip       VARCHAR(45),                          -- 客户端 IP
    user_agent      VARCHAR(512),                         -- User-Agent
    device_id       VARCHAR(128),                         -- 设备标识
    service_url     VARCHAR(512),                         -- 请求的 service URL
    handler_name    VARCHAR(64),                          -- 处理器名称
    created_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 按用户查询审计记录
CREATE INDEX IF NOT EXISTS idx_audit_log_user ON cas_auth_audit_log(user_id, created_time DESC);

-- 按时间范围查询（用于报表和风控分析）
CREATE INDEX IF NOT EXISTS idx_audit_log_time ON cas_auth_audit_log(created_time DESC);

-- 按认证结果查询失败记录
CREATE INDEX IF NOT EXISTS idx_audit_log_result ON cas_auth_audit_log(auth_result, created_time DESC);
