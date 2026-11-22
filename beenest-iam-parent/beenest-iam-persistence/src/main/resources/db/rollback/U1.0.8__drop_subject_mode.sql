-- ============================================================
-- 回滚 V1.0.8(主体标识策略)
-- 逆序执行:先删客户端覆盖列 → 删域级策略列。
-- 注意:回滚后 sub 一律取登录名;已按 USER_ID 接入的应用需要重新映射,
--       故回滚只在尚未有应用切换到 USER_ID 时才是无损的。
-- ============================================================

ALTER TABLE iam_client DROP COLUMN IF EXISTS subject_mode;
ALTER TABLE iam_realm DROP COLUMN IF EXISTS subject_mode;
