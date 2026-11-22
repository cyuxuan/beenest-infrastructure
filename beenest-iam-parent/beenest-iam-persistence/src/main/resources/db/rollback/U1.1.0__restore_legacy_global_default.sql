-- 回滚 V1.1.0:新应用令牌收敛档默认值退回 LEGACY_GLOBAL。
-- 只改默认值,不改存量行——迁移本身也没有改动存量应用的值,
-- 因此回滚后各应用当前的 token_profile 保持不变。

ALTER TABLE iam_application_service
    ALTER COLUMN token_profile SET DEFAULT 'LEGACY_GLOBAL';

COMMENT ON COLUMN iam_application_service.token_profile IS
    '令牌收敛档:SCOPED 按应用裁剪 / LEGACY_GLOBAL 迁移期兼容;裁剪实现前保持 LEGACY_GLOBAL';
