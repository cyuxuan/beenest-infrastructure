-- CAS 统一用户表
-- 支持多渠道认证：微信/抖音/支付宝小程序、手机短信、用户名密码、APP
-- 状态编码：1=正常, 2=锁定, 3=禁用, 4=删除
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

-- ============================================================
-- 条件唯一索引：排除已删除(status=4)和 NULL 值
-- 这是多渠道用户合并的基础，防止同一标识注册多个账号
-- ============================================================

-- userId：全局唯一（含删除的，因为 userId 是永久标识）
-- 已通过列定义 UNIQUE 约束实现

-- 手机号：非删除且非 NULL 时唯一（允许多个未绑定手机号的用户共存）
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_phone
    ON cas_user(phone) WHERE status != 4 AND phone IS NOT NULL;

-- 用户名：非删除且非 NULL 时唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_username
    ON cas_user(username) WHERE status != 4 AND username IS NOT NULL;

-- 邮箱：非删除且非 NULL 时唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_user_email
    ON cas_user(email) WHERE status != 4 AND email IS NOT NULL;

-- 微信 openid + loginType：非删除且非 NULL 时唯一
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

-- ============================================================
-- 性能索引
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_cas_user_status ON cas_user(status);
