-- 回滚 V1.1.7:移除租户级主题选择。
-- 删除后所有域回到跟随全局 iam.theme.active 的行为;
-- 各域已配置的主题目录名一并丢失,重新升级需在控制台重新选择。

ALTER TABLE iam_realm DROP COLUMN IF EXISTS theme;
