-- 回滚 V1.3.5:移除应用受众列。
-- 列上已配置的受众会随之丢失;回滚后访问令牌的 aud 回到客户端 id,
-- 按受众校验的资源服务器需要同时撤下对应的受众配置。

ALTER TABLE iam_application_service
    DROP COLUMN IF EXISTS audience;
