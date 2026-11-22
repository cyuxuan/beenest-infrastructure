-- ============================================================
-- V1.3.5 应用受众(访问令牌 aud)
--   1. iam_application_service 增加 audience:写入访问令牌的 aud,
--      资源服务器据此判断"令牌是不是签给本系统的"。
--   2. 同一后端有多个入口(Web / 小程序)时,各入口的应用服务登记
--      同一个受众,资源服务器只校验一个值。
-- 语义要点:
--   - 可空:留空按 urn:iam:app:{app_code} 派生,存量应用升级后
--     自动获得按应用隔离的受众,不需要逐条回填
--   - 只影响访问令牌;ID Token 的 aud 按 OIDC 规范恒等于 client_id
-- ============================================================

ALTER TABLE iam_application_service
    ADD COLUMN IF NOT EXISTS audience VARCHAR(200);

COMMENT ON COLUMN iam_application_service.audience IS
    '访问令牌受众(aud);同一后端的多个入口登记同一值,空值按 urn:iam:app:{app_code} 派生';
