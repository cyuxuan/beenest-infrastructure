-- ============================================================
-- beenest-cas schema 初始化
-- Apereo CAS 7.3.x 统一认证中心
-- ============================================================
-- 此脚本在 beenest 数据库的 beenest_cas schema 中创建表结构。
-- Apereo CAS JPA Service Registry 会自动创建 registered_service 表，
-- 此处只创建业务自定义表（Flyway 迁移脚本的 SQL 合并）。
-- ============================================================

-- 设置 search_path 到 beenest_cas schema
SET search_path TO beenest_cas;

-- ============================================================
-- 1. CAS 统一用户表
-- 来源: V1.0.0__create_unified_user.sql
-- 支持多渠道认证：微信/抖音/支付宝小程序、手机短信、用户名密码、APP
-- 状态编码：1=正常, 2=锁定, 3=禁用, 4=删除
-- ============================================================
CREATE TABLE IF NOT EXISTS cas_user (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(32) NOT NULL UNIQUE,
    user_type       VARCHAR(20) DEFAULT 'CUSTOMER',
    identity        VARCHAR(20),
    source          VARCHAR(20) DEFAULT 'WEB',
    login_type      VARCHAR(20),
    -- 微信
    openid          VARCHAR(64),
    unionid         VARCHAR(64),
    -- 抖音
    douyin_openid   VARCHAR(64),
    douyin_unionid  VARCHAR(64),
    -- 支付宝
    alipay_uid      VARCHAR(64),
    alipay_openid   VARCHAR(64),
    -- 基础信息
    username        VARCHAR(64),
    nickname        VARCHAR(128),
    avatar_url      VARCHAR(512),
    phone           VARCHAR(20),
    email           VARCHAR(128),
    password_hash   VARCHAR(256),
    -- 验证状态
    phone_verified  BOOLEAN DEFAULT FALSE,
    email_verified  BOOLEAN DEFAULT FALSE,
    -- MFA
    mfa_enabled     BOOLEAN DEFAULT FALSE,
    mfa_secret_encrypted VARCHAR(256),
    -- 账号安全
    status          SMALLINT DEFAULT 1,
    failed_login_count SMALLINT DEFAULT 0,
    lock_until_time TIMESTAMP,
    -- 登录追踪
    last_login_time     TIMESTAMP,
    last_login_ip       VARCHAR(45),
    last_login_ua       VARCHAR(512),
    last_login_device   VARCHAR(128),
    token_version       INT DEFAULT 1,
    created_time        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 条件唯一索引：排除已删除(status=4)和 NULL 值
-- 手机号：非删除且非 NULL 时唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_phone
    ON cas_user(phone) WHERE status != 4 AND phone IS NOT NULL;

-- 用户名：非删除且非 NULL 时唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_username
    ON cas_user(username) WHERE status != 4 AND username IS NOT NULL;

-- 邮箱：非删除且非 NULL 时唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_email
    ON cas_user(email) WHERE status != 4 AND email IS NOT NULL;

-- 微信 openid：非删除且非 NULL 时唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_openid
    ON cas_user(openid) WHERE status != 4 AND openid IS NOT NULL;

-- 微信 unionid：非删除且非 NULL 时唯一（跨应用）
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_unionid
    ON cas_user(unionid) WHERE status != 4 AND unionid IS NOT NULL;

-- 抖音 openid：非删除且非 NULL 时唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_douyin_openid
    ON cas_user(douyin_openid) WHERE status != 4 AND douyin_openid IS NOT NULL;

-- 抖音 unionid：非删除且非 NULL 时唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_douyin_unionid
    ON cas_user(douyin_unionid) WHERE status != 4 AND douyin_unionid IS NOT NULL;

-- 支付宝 UID：非删除且非 NULL 时唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_alipay_uid
    ON cas_user(alipay_uid) WHERE status != 4 AND alipay_uid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cas_user_status ON cas_user(status);

-- ============================================================
-- 2. 应用访问控制表
-- 来源: V1.0.1__create_cas_app_access.sql
-- 控制用户对特定应用的访问权限，ST 签发前校验
-- ============================================================
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

-- ============================================================
-- 3. 用户变更日志表
-- 来源: V1.0.2__create_cas_user_change_log.sql
-- 记录用户数据的增删改，供下游应用服务拉取同步
-- ============================================================
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

-- ============================================================
-- 4. 同步策略配置表
-- 来源: V1.0.3__create_cas_sync_strategy.sql
-- 定义每个应用的用户数据同步策略
-- ============================================================
CREATE TABLE IF NOT EXISTS cas_sync_strategy (
    id              BIGSERIAL PRIMARY KEY,
    service_id      BIGINT NOT NULL UNIQUE,
    push_enabled    BOOLEAN DEFAULT FALSE,
    push_url        VARCHAR(512),
    push_secret     VARCHAR(256),
    push_events     VARCHAR(256),
    pull_enabled    BOOLEAN DEFAULT TRUE,
    max_retries     SMALLINT DEFAULT 3,
    retry_interval  SMALLINT DEFAULT 60,
    created_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sync_strategy_service ON cas_sync_strategy(service_id);

-- ============================================================
-- 5. 认证审计日志表
-- 来源: V1.0.4__create_cas_auth_audit_log.sql
-- 记录所有认证事件（成功/失败/锁定），供安全追溯和风控分析
-- ============================================================
CREATE TABLE IF NOT EXISTS cas_auth_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(32),
    principal       VARCHAR(128),
    auth_type       VARCHAR(30) NOT NULL,
    auth_result     VARCHAR(20) NOT NULL,
    failure_reason  VARCHAR(256),
    client_ip       VARCHAR(45),
    user_agent      VARCHAR(512),
    device_id       VARCHAR(128),
    service_url     VARCHAR(512),
    handler_name    VARCHAR(64),
    created_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_log_user ON cas_auth_audit_log(user_id, created_time DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_time ON cas_auth_audit_log(created_time DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_result ON cas_auth_audit_log(auth_result, created_time DESC);

insert into beenest_cas.cas_service_credential (id, service_id, secret_hash, secret_salt, secret_version, state, created_time, updated_time) values (1, 10001, '9zl7BTJTXZGhG7JWSo/+CxlKbJ8Qj+UWxMmwX6t1h0chCtzzRtpeFlLMpUZJ4D1nPEQ1FGciSueRuNT0+dmhnqTqEad7AWfgzXeVsWILFhNwYoJuTKCK3xQohI4=', 'fffdfee46e26bb86845c88ebc41fdf2f', 1, 'ACTIVE', '2026-04-17 09:45:54.342680', '2026-04-17 09:45:54.342680');

