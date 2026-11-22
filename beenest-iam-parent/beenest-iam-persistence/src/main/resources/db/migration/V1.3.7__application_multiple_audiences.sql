-- ============================================================
-- V1.3.7 应用受众支持多个
--   audience 由"单个受众"扩展为"逗号分隔的受众列表":
--   API 层是 List<String>,库内以逗号拼接存储,与 client.scopes、
--   client.redirect_uris 的存法一致。
-- 语义要点:
--   - 单个值天然是只有一个元素的列表,存量数据无需迁移
--   - 列宽放宽到 1000,容纳最多 5 个 200 字符的受众
--   - 受众值不允许包含英文逗号,否则读回时会被拆开
-- ============================================================

ALTER TABLE iam_application_service
    ALTER COLUMN audience TYPE VARCHAR(1000);

COMMENT ON COLUMN iam_application_service.audience IS
    '访问令牌受众(aud),多个以英文逗号分隔;同一后端的多个入口登记同一组受众,空值按 urn:iam:app:{app_code} 派生';
