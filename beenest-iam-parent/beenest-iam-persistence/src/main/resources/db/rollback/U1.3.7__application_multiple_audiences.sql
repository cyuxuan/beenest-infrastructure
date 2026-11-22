-- 回滚 V1.3.7:受众列收窄回单值宽度。
-- 超长内容会被截断到 200 字符,多受众配置随之失效;回滚前先确认没有应用
-- 依赖第二个及以后的受众。

UPDATE iam_application_service
SET audience = LEFT(audience, 200)
WHERE audience IS NOT NULL
  AND LENGTH(audience) > 200;

ALTER TABLE iam_application_service
    ALTER COLUMN audience TYPE VARCHAR(200);
