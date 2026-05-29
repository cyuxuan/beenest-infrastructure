-- ============================================================
-- Beenest CAS 数据库初始化脚本
-- 合并自 V1.0.0 ~ V2.0.3 迁移脚本，仅保留最终存活的表结构
-- 目标：PostgreSQL beenest_cas schema
-- ============================================================

-- ============================================================
-- 1. 统一用户表
-- 支持多渠道认证：微信/抖音/支付宝小程序、手机短信、用户名密码、APP
-- 状态编码：1=正常, 2=锁定, 3=禁用, 4=删除
-- ============================================================
CREATE TABLE IF NOT EXISTS cas_user (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 VARCHAR(32) NOT NULL UNIQUE,
    user_type               VARCHAR(20) DEFAULT 'CUSTOMER',
    identity                VARCHAR(20),
    roles                   VARCHAR(500)    DEFAULT NULL,
    source                  VARCHAR(20) DEFAULT 'WEB',
    login_type              VARCHAR(20),
    -- 微信
    openid                  VARCHAR(64),
    unionid                 VARCHAR(64),
    -- 抖音
    douyin_openid           VARCHAR(64),
    douyin_unionid          VARCHAR(64),
    -- 支付宝
    alipay_uid              VARCHAR(64),
    alipay_openid           VARCHAR(64),
    -- 基础信息
    username                VARCHAR(64),
    nickname                VARCHAR(128),
    avatar_url              VARCHAR(512),
    phone                   VARCHAR(20),
    email                   VARCHAR(128),
    password_hash           VARCHAR(256),
    -- 验证状态
    phone_verified          BOOLEAN DEFAULT FALSE,
    email_verified          BOOLEAN DEFAULT FALSE,
    -- MFA
    mfa_enabled             BOOLEAN DEFAULT FALSE,
    -- 账号安全
    status                  SMALLINT DEFAULT 1,
    failed_login_count      SMALLINT DEFAULT 0,
    lock_until_time         TIMESTAMP,
    -- 密码管理（CAS 原生密码管理模块）
    password_changed_time   TIMESTAMP,
    password_expiry_time    TIMESTAMP,
    must_change_password    BOOLEAN DEFAULT FALSE,
    -- 登录追踪
    last_login_time         TIMESTAMP,
    last_login_ip           VARCHAR(45),
    last_login_ua           VARCHAR(512),
    last_login_device       VARCHAR(128),
    token_version           INT DEFAULT 1,
    created_time            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time            TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 条件唯一索引：排除已删除(status=4)和 NULL 值，多渠道用户合并的基础
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_phone
    ON cas_user(phone) WHERE status != 4 AND phone IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_username
    ON cas_user(username) WHERE status != 4 AND username IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_email
    ON cas_user(email) WHERE status != 4 AND email IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_openid
    ON cas_user(openid) WHERE status != 4 AND openid IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_unionid
    ON cas_user(unionid) WHERE status != 4 AND unionid IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_douyin_openid
    ON cas_user(douyin_openid) WHERE status != 4 AND douyin_openid IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_douyin_unionid
    ON cas_user(douyin_unionid) WHERE status != 4 AND douyin_unionid IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_alipay_uid
    ON cas_user(alipay_uid) WHERE status != 4 AND alipay_uid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cas_user_status ON cas_user(status);

-- ============================================================
-- 2. 委托/代理登录表 (Surrogate Authentication)
-- 允许管理员以其他用户身份登录，用于排查问题
-- ============================================================
CREATE TABLE IF NOT EXISTS cas_surrogate (
    id              BIGSERIAL       PRIMARY KEY,
    principal       VARCHAR(255)    NOT NULL,
    surrogate_user  VARCHAR(255)    NOT NULL,
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_surrogate_pair UNIQUE (principal, surrogate_user)
);

CREATE INDEX IF NOT EXISTS idx_surrogate_principal ON cas_surrogate(principal);

COMMENT ON TABLE cas_surrogate IS '委托/代理登录配置表';
COMMENT ON COLUMN cas_surrogate.principal IS '授权人用户名（被允许代理登录的管理员）';
COMMENT ON COLUMN cas_surrogate.surrogate_user IS '可代理的目标用户名';

-- ============================================================
-- 3. 使用条款接受记录表 (Acceptable Usage Policy)
-- 记录用户对使用条款的接受情况
-- ============================================================
CREATE TABLE IF NOT EXISTS aup_usage_terms (
    id              BIGSERIAL       PRIMARY KEY,
    principal       VARCHAR(255)    NOT NULL,
    term            TEXT            NOT NULL,
    accepted        BOOLEAN         DEFAULT FALSE,
    accepted_on     TIMESTAMP,
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_aup_principal UNIQUE (principal)
);

CREATE INDEX IF NOT EXISTS idx_aup_principal ON aup_usage_terms(principal);

COMMENT ON TABLE aup_usage_terms IS '使用条款接受记录表';
COMMENT ON COLUMN aup_usage_terms.principal IS '用户标识';
COMMENT ON COLUMN aup_usage_terms.term IS '使用条款内容或版本标识';
COMMENT ON COLUMN aup_usage_terms.accepted IS '是否已接受使用条款';

-- 初始化：插入默认使用条款
INSERT INTO aup_usage_terms (principal, term, accepted, accepted_on)
VALUES ('SYSTEM_DEFAULT', 'Beenest 平台统一认证使用条款 v1.0', TRUE, CURRENT_TIMESTAMP)
ON CONFLICT (principal) DO NOTHING;

-- ============================================================
-- 4. Consent 决策表 (属性授权记录)
-- 配合 Consent Webflow 和 /actuator/attributeConsent 使用
-- ============================================================
CREATE SEQUENCE IF NOT EXISTS hibernate_sequence START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS consent_decision_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS consent_decision (
    id                  BIGSERIAL PRIMARY KEY,
    principal           VARCHAR(255) NOT NULL,
    service             VARCHAR(255) NOT NULL,
    created_date        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    options             INTEGER      NOT NULL DEFAULT 0,
    reminder            BIGINT       NOT NULL DEFAULT 14,
    reminder_time_unit  INTEGER      NOT NULL DEFAULT 7,
    tenant              VARCHAR(255),
    attributes          TEXT,
    CONSTRAINT uq_consent_decision_principal_service UNIQUE (principal, service)
);

CREATE INDEX IF NOT EXISTS idx_consent_decision_principal ON consent_decision(principal);
CREATE INDEX IF NOT EXISTS idx_consent_decision_service ON consent_decision(service);
