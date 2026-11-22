-- 回滚 V1.3.6:恢复"应用自发起"布尔标记。
-- 原值已随列删除,统一置 FALSE:回滚后所有应用都按"门户直达入口"处理,
-- 需要代发授权地址的场景由管理员逐个改回 TRUE。

ALTER TABLE iam_application_service
    ADD COLUMN IF NOT EXISTS sso_self_initiated BOOLEAN NOT NULL DEFAULT FALSE;
