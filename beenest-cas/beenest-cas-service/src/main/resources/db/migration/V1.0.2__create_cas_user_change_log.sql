-- 用户变更日志表
-- 记录用户数据的增删改，供下游应用服务拉取同步
CREATE TABLE IF NOT EXISTS cas_user_change_log (
    id           BIGSERIAL PRIMARY KEY,
    user_id      VARCHAR(32) NOT NULL,
    change_type  VARCHAR(30) NOT NULL,
    old_data     TEXT,
    new_data     TEXT,
    synced       BOOLEAN DEFAULT FALSE,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_change_log_sync ON cas_user_change_log(synced, created_time);
