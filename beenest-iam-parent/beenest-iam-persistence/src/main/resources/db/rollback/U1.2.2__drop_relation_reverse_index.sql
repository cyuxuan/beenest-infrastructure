-- ============================================================
-- 回滚 V1.2.2(关系表反向索引)
-- 索引不承载数据,直接删除即可;删除后查询结果不变,只影响性能。
-- ============================================================

DROP INDEX IF EXISTS idx_iam_user_group_group;
DROP INDEX IF EXISTS idx_iam_role_permission_permission;
DROP INDEX IF EXISTS idx_iam_role_menu_menu;
DROP INDEX IF EXISTS idx_iam_menu_permission;
DROP INDEX IF EXISTS idx_iam_user_permission_permission;
