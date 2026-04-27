-- 同步策略配置表
-- 定义每个应用的用户数据同步策略（推送/拉取、订阅变更类型、Webhook URL等）
CREATE TABLE IF NOT EXISTS cas_sync_strategy (
    id              BIGSERIAL PRIMARY KEY,
    service_id      BIGINT NOT NULL UNIQUE,       -- 关联 registered_service
    -- 推送配置
    push_enabled    BOOLEAN DEFAULT FALSE,
    push_url        VARCHAR(512),                  -- Webhook 推送 URL
    push_secret     VARCHAR(256),                  -- Webhook 签名密钥
    push_events     VARCHAR(256),                  -- 订阅的变更类型，逗号分隔：CREATE,UPDATE,DELETE,STATUS_CHANGE
    -- 拉取配置
    pull_enabled    BOOLEAN DEFAULT TRUE,
    -- 重试配置
    max_retries     SMALLINT DEFAULT 3,
    retry_interval  SMALLINT DEFAULT 60,          -- 重试间隔（秒）
    created_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sync_strategy_service ON cas_sync_strategy(service_id);
