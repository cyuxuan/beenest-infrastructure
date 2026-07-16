-- ============================================================
-- V1_0_11: 应用凭证表 — 实现业务系统级密钥隔离
--
-- 设计说明：
--   每个业务系统（DRONE/SHOP 等）拥有独立的认证密钥，
--   替代全局共享的 INTERNAL_TOKEN / INTERNAL_SIGN_SECRET / MQ_SIGN_SECRET。
--   密钥隔离确保单业务系统密钥泄露不影响其他系统。
--
--   app_secret: BCrypt 哈希存储（仅验证，不可逆）
--   sign_secret: AES-256-GCM 加密存储（HMAC 签名验证需要明文密钥，与 mq_secret 共用主密钥）
--   mq_secret: AES-256-GCM 加密存储（支付中台需用明文签发消息）
--   allowed_networks: per-app IP 白名单（CIDR 网段），为空时不做 IP 限制
-- ============================================================

CREATE TABLE IF NOT EXISTS ds_app_credential (
    id               BIGSERIAL       PRIMARY KEY,
    app_id           VARCHAR(32)     NOT NULL,
    app_name         VARCHAR(64)     NOT NULL,
    app_secret       VARCHAR(128)    NOT NULL,
    sign_secret      VARCHAR(128)    NOT NULL,
    mq_secret        VARCHAR(256)    NOT NULL,
    allowed_networks TEXT,
    status           VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    description      VARCHAR(256),
    created_by       VARCHAR(64),
    create_time      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by       VARCHAR(64),
    update_time      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_credential_app_id UNIQUE (app_id),
    CONSTRAINT chk_app_credential_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

COMMENT ON TABLE ds_app_credential IS '应用凭证表，存储各业务系统的独立认证密钥';
COMMENT ON COLUMN ds_app_credential.app_id IS '业务系统标识（DRONE/SHOP），与业务表 app_id 对应';
COMMENT ON COLUMN ds_app_credential.app_name IS '应用名称（如：无人机系统、商城系统）';
COMMENT ON COLUMN ds_app_credential.app_secret IS '内部 API 静态令牌（BCrypt 哈希存储，原始值仅初始化时返回一次）';
COMMENT ON COLUMN ds_app_credential.sign_secret IS '内部 API HMAC 签名密钥（AES-256-GCM 加密存储，HMAC 验证需要明文密钥，主密钥通过 PAYMENT_MQ_MASTER_KEY 环境变量注入）';
COMMENT ON COLUMN ds_app_credential.mq_secret IS 'MQ 消息签名密钥（AES-256-GCM 加密存储，主密钥通过 PAYMENT_MQ_MASTER_KEY 环境变量注入）';
COMMENT ON COLUMN ds_app_credential.allowed_networks IS '允许的 IP/CIDR 网段列表（逗号分隔，如 10.0.0.0/8,172.16.0.0/12），为空时不做 IP 限制（所有 IP 可访问）';
COMMENT ON COLUMN ds_app_credential.status IS '状态: ACTIVE(正常), DISABLED(停用)';
COMMENT ON COLUMN ds_app_credential.description IS '描述信息';
COMMENT ON COLUMN ds_app_credential.created_by IS '创建人';
COMMENT ON COLUMN ds_app_credential.create_time IS '创建时间';
COMMENT ON COLUMN ds_app_credential.updated_by IS '更新人';
COMMENT ON COLUMN ds_app_credential.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_app_credential_status ON ds_app_credential(status);

-- ============================================================
-- 初始化数据：占位密钥，部署后通过管理 API 轮换为真实值
-- PLACEHOLDER 为无效 BCrypt 哈希，需通过 AppCredentialController 创建/轮换
-- ============================================================

INSERT INTO ds_app_credential (app_id, app_name, app_secret, sign_secret, mq_secret, status, created_by)
VALUES ('DRONE', '无人机系统', 'PLACEHOLDER', 'PLACEHOLDER', 'PLACEHOLDER', 'ACTIVE', 'SYSTEM');

INSERT INTO ds_app_credential (app_id, app_name, app_secret, sign_secret, mq_secret, status, created_by)
VALUES ('SHOP', '商城系统', 'PLACEHOLDER', 'PLACEHOLDER', 'PLACEHOLDER', 'ACTIVE', 'SYSTEM');
