-- 应用访问控制表
-- 控制用户对特定应用的访问权限，ST 签发前校验
CREATE TABLE IF NOT EXISTS cas_app_access (
    id           BIGSERIAL PRIMARY KEY,
    user_id      VARCHAR(32) NOT NULL,
    service_id   BIGINT NOT NULL,
    access_level VARCHAR(20) DEFAULT 'BASIC',
    granted_by   VARCHAR(32),
    reason       VARCHAR(256),
    expire_time  TIMESTAMP,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, service_id)
);

CREATE INDEX IF NOT EXISTS idx_app_access_user ON cas_app_access(user_id);
CREATE INDEX IF NOT EXISTS idx_app_access_service ON cas_app_access(service_id);
