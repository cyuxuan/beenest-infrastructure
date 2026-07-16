-- ============================================================
-- V1_0_12: sign_secret 存储方式从 BCrypt 改为 AES-256-GCM 加密
--
-- 变更原因：
--   HMAC-SHA256 签名验证需要明文密钥重新计算签名进行比对，
--   BCrypt 是单向哈希无法还原明文，导致 per-app 签名验证无法实现。
--   改为 AES-256-GCM 加密存储后，支付中台可解密获取明文用于 HMAC 计算，
--   与 mq_secret 的存储方式保持一致。
--
--   app_secret 仍使用 BCrypt 哈希（仅做令牌验证，不需要明文）
-- ============================================================

-- 1. 扩展 sign_secret 字段长度（AES-GCM 密文比 BCrypt 哈希更长）
ALTER TABLE ds_app_credential ALTER COLUMN sign_secret TYPE VARCHAR(256);

-- 2. 更新字段注释
COMMENT ON COLUMN ds_app_credential.sign_secret IS '内部 API HMAC 签名密钥（AES-256-GCM 加密存储，主密钥通过 PAYMENT_MQ_MASTER_KEY 环境变量注入，与 mq_secret 共用主密钥）';
