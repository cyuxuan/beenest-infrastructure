-- Phase 6: 创建 CAS 原生 Surrogate（代理登录）和 AUP（使用条款）表
-- 这些表由 CAS 原生模块使用，替代之前的自定义管理功能

-- ===================================================================
-- 委托/代理登录表 (Surrogate Authentication)
-- 允许管理员以其他用户身份登录，用于排查问题
-- ===================================================================
CREATE TABLE IF NOT EXISTS cas_surrogate (
    id              BIGSERIAL       PRIMARY KEY,
    principal       VARCHAR(255)    NOT NULL,  -- 授权人（被允许代理的用户）
    surrogate_user  VARCHAR(255)    NOT NULL,  -- 可代理的目标用户
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_surrogate_pair UNIQUE (principal, surrogate_user)
);

CREATE INDEX IF NOT EXISTS idx_surrogate_principal ON cas_surrogate(principal);

COMMENT ON TABLE cas_surrogate IS '委托/代理登录配置表';
COMMENT ON COLUMN cas_surrogate.principal IS '授权人用户名（被允许代理登录的管理员）';
COMMENT ON COLUMN cas_surrogate.surrogate_user IS '可代理的目标用户名';

-- ===================================================================
-- 使用条款接受记录表 (Acceptable Usage Policy)
-- 记录用户对使用条款的接受情况
-- ===================================================================
CREATE TABLE IF NOT EXISTS aup_usage_terms (
    id              BIGSERIAL       PRIMARY KEY,
    principal       VARCHAR(255)    NOT NULL,  -- 用户标识
    term            TEXT            NOT NULL,  -- 条款内容/版本标识
    accepted        BOOLEAN         DEFAULT FALSE,
    accepted_on     TIMESTAMP,                  -- 接受时间
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_aup_principal UNIQUE (principal)
);

CREATE INDEX IF NOT EXISTS idx_aup_principal ON aup_usage_terms(principal);

COMMENT ON TABLE aup_usage_terms IS '使用条款接受记录表';
COMMENT ON COLUMN aup_usage_terms.principal IS '用户标识';
COMMENT ON COLUMN aup_usage_terms.term IS '使用条款内容或版本标识';
COMMENT ON COLUMN aup_usage_terms.accepted IS '是否已接受使用条款';

-- 初始化：插入默认使用条款（首次启动时需要用户接受）
INSERT INTO aup_usage_terms (principal, term, accepted, accepted_on)
VALUES ('SYSTEM_DEFAULT', 'Beenest 平台统一认证使用条款 v1.0', TRUE, CURRENT_TIMESTAMP)
ON CONFLICT (principal) DO NOTHING;
