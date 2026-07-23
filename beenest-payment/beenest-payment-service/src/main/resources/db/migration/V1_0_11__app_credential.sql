-- ============================================================
-- V1_0_11: 应用凭证表 — 实现业务系统级密钥隔离
--
-- 设计说明：
--   每个业务系统（DRONE/SHOP 等）拥有独立的认证密钥，
--   替代全局共享的 INTERNAL_TOKEN / INTERNAL_SIGN_SECRET / MQ_SIGN_SECRET。
--   密钥隔离确保单业务系统密钥泄露不影响其他系统。
--
--   密钥体系（2 secret 模型）：
--   app_secret: 明文存储（令牌认证 + HMAC 签名共用，DB 访问控制保护）
--   mq_secret:  明文存储（MQ 消息签名，DB 访问控制保护）
--
--   安全说明：
--   app_secret / mq_secret 运行时必须以明文参与 HMAC 计算，
--   AES 加密存储只是"安全幻觉"（主密钥与密钥在同一进程内存中）。
--   明文存储 + DB 访问控制是更诚实、更简洁的方案。
--   生产环境可通过 Jasypt 列级加密或 PostgreSQL pgcrypto 增强 DB 静态保护。
--   allowed_networks: per-app IP 白名单（CIDR 网段），为空时不做 IP 限制
-- ============================================================

CREATE TABLE IF NOT EXISTS ds_app_credential (
    id               BIGSERIAL       PRIMARY KEY,
    app_id           VARCHAR(32)     NOT NULL,
    app_name         VARCHAR(64)     NOT NULL,
    app_secret       VARCHAR(128)    NOT NULL,
    mq_secret        VARCHAR(128)    NOT NULL,
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
COMMENT ON COLUMN ds_app_credential.app_secret IS '内部 API 密钥（明文存储，令牌认证 + HMAC 签名共用，通过 DB 访问控制保护，原始值仅创建/轮换时返回一次）';
COMMENT ON COLUMN ds_app_credential.mq_secret IS 'MQ 消息签名密钥（明文存储，通过 DB 访问控制保护，原始值仅创建/轮换时返回一次）';
COMMENT ON COLUMN ds_app_credential.allowed_networks IS '允许的 IP/CIDR 网段列表（逗号分隔，如 10.0.0.0/8,172.16.0.0/12），为空时不做 IP 限制（所有 IP 可访问）';
COMMENT ON COLUMN ds_app_credential.status IS '状态: ACTIVE(正常), DISABLED(停用)';
COMMENT ON COLUMN ds_app_credential.description IS '描述信息';
COMMENT ON COLUMN ds_app_credential.created_by IS '创建人';
COMMENT ON COLUMN ds_app_credential.create_time IS '创建时间';
COMMENT ON COLUMN ds_app_credential.updated_by IS '更新人';
COMMENT ON COLUMN ds_app_credential.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_app_credential_status ON ds_app_credential(status);

-- ============================================================
-- 初始化数据：预生成的安全随机密钥
-- 下游服务（drone-system 等）需将对应密钥配置到 payment.client.app-secret
-- ============================================================

INSERT INTO ds_app_credential (app_id, app_name, app_secret, mq_secret, allowed_networks, status, created_by)
VALUES ('DRONE', '无人机系统',
        'e036e98d5699bf3a004bf76d7c60155ee052fe16d96be1414d5e39ac946ce9fd',
        '1121190f7ec80b1726be4bf8e560531758828a9fda642fe34543be08314d8066',
        NULL,
        'ACTIVE', 'SYSTEM');

INSERT INTO ds_app_credential (app_id, app_name, app_secret, mq_secret, allowed_networks, status, created_by)
VALUES ('SHOP', '商城系统',
        '942cb571e509a95273f2d08e705fd6de036459fcac8264690193a5b54602b2b5',
        'db1ccd31918c736ce46898a6e0902d7780df39bfa96908a46b617468f7937919',
        NULL,
        'ACTIVE', 'SYSTEM');
