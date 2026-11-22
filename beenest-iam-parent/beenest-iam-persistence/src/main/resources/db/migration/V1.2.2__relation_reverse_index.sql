-- =====================================================================
-- V1.2.2 关系表的反向索引
-- ---------------------------------------------------------------------
-- 背景:
--   授权类关系表(用户-组、角色-权限、角色-菜单、用户特批)的唯一索引
--   都是"正向"的:按 (持有者, 被授予者) 排列,服务的是"某人/某角色有什么"。
--   但管理端还有大量"反向"查询——"这个权限被谁引用""这个菜单授给了哪些角色"
--   "这个组里有哪些人"——它们按被授予者过滤,用不上正向索引的前缀,
--   只能走全表扫描。
--
--   这类表随授权关系线性增长,且删除前的引用校验(isInUse)每次都要问一遍,
--   越到后期越慢。补反向索引即可,不改变任何查询语义。
--
-- 全部使用部分索引(WHERE deleted = FALSE):软删除的行不参与查询,
-- 不必为历史数据付索引体积。
-- 岗位维度不需要在此补:iam_position_role(position_id, role_id) 的唯一索引
-- 已覆盖 position_id 前缀,任职关系落在 iam_user_group.position_id 上,
-- V1.2.0 也已建好对应索引。
-- =====================================================================

-- 组成员列表、组成员计数、按组删除关联(UserGroupMapper.xml)
CREATE INDEX IF NOT EXISTS idx_iam_user_group_group
    ON iam_user_group(group_id) WHERE deleted = FALSE;

-- 权限删除前的引用校验、按权限反查角色(RolePermissionMapper.xml)
CREATE INDEX IF NOT EXISTS idx_iam_role_permission_permission
    ON iam_role_permission(permission_id) WHERE deleted = FALSE;

-- 菜单删除前的关联清理、按菜单反查角色(RoleMenuMapper.xml)
CREATE INDEX IF NOT EXISTS idx_iam_role_menu_menu
    ON iam_role_menu(menu_id) WHERE deleted = FALSE;

-- 菜单挂载权限的派生查询、权限删除前的引用校验(MenuMapper.xml)
CREATE INDEX IF NOT EXISTS idx_iam_menu_permission
    ON iam_menu(permission_id) WHERE deleted = FALSE AND permission_id IS NOT NULL;

-- 权限删除前的引用校验:按权限反查用户特批(UserPermissionMapper.xml)
CREATE INDEX IF NOT EXISTS idx_iam_user_permission_permission
    ON iam_user_permission(permission_id) WHERE deleted = FALSE;
