package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.mapper.MenuMapper;
import club.beenest.iam.persistence.mapper.RoleMenuMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 菜单存储(iam_menu / iam_role_menu)。
 * <p>菜单按应用归属,树形关系在应用层拼装;菜单与角色-菜单授予变更后抬升权限代次。</p>
 */
@Component
public class IamMenuManager {

    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;
    /** 权限缓存代次:菜单相关变更后自增 */
    private final IamPermissionEpoch permissionEpoch;

    public IamMenuManager(MenuMapper menuMapper, RoleMenuMapper roleMenuMapper,
                          IamPermissionEpoch permissionEpoch) {
        this.menuMapper = menuMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.permissionEpoch = permissionEpoch;
    }

    /**
     * 按主键查询(已过滤删除行)。
     *
     * @param id 菜单主键
     * @return 菜单;不存在返回 null
     */
    public Menu getById(Long id) {
        return menuMapper.selectById(id);
    }

    /**
     * 按主键集合查询。
     *
     * @param ids 菜单主键集合
     * @return 菜单列表;集合为空时返回空列表
     */
    public List<Menu> listByIds(List<Long> ids) {
        return (ids == null || ids.isEmpty()) ? List.of() : menuMapper.selectByIds(ids);
    }

    /**
     * 按域查询全部菜单。
     *
     * @param realmId 身份域主键
     * @return 菜单列表
     */
    public List<Menu> listByRealmId(Long realmId) {
        return menuMapper.selectByRealmId(realmId);
    }

    /**
     * 按域与应用查询菜单(菜单管理与应用菜单下发的数据源)。
     *
     * @param realmId 身份域主键
     * @param appServiceId 应用服务主键;为空返回空列表
     * @return 菜单列表
     */
    public List<Menu> listByAppServiceId(Long realmId, Long appServiceId) {
        if (appServiceId == null) {
            return List.of();
        }
        return menuMapper.selectByAppServiceId(realmId, appServiceId);
    }

    /**
     * 新建菜单,主键回填,并抬升该域权限代次。
     *
     * @param menu 菜单实体
     * @return 受影响行数
     */
    public int create(Menu menu) {
        int rows = menuMapper.insert(menu);
        permissionEpoch.bump(menu.getRealmId());
        return rows;
    }

    /**
     * 更新非空字段,并抬升该域权限代次。
     *
     * @param menu 菜单实体,需含主键
     * @return 受影响行数
     */
    public int update(Menu menu) {
        int rows = menuMapper.updateById(menu);
        permissionEpoch.bump(menu.getRealmId());
        return rows;
    }

    /**
     * 逻辑删除菜单及其全部后代,同步清理角色-菜单关联,并抬升权限代次。
     *
     * @param realmId 身份域主键
     * @param id 菜单主键
     * @return 被删除的菜单主键(含后代);菜单不存在时返回空列表
     */
    @Transactional
    public List<Long> removeWithChildren(Long realmId, Long id) {
        List<Menu> all = menuMapper.selectByRealmId(realmId);
        List<Long> ids = collectSubtree(all, id);
        if (ids.isEmpty()) {
            return ids;
        }
        roleMenuMapper.softDeleteByMenuIds(ids);
        menuMapper.softDeleteByIds(ids);
        // 菜单删除会带走角色-菜单派生权限,写库后失效缓存
        permissionEpoch.bump(realmId);
        return ids;
    }

    /**
     * 查询角色已授予的菜单。
     *
     * @param roleId 角色主键
     * @return 菜单列表
     */
    public List<Menu> listByRoleId(Long roleId) {
        List<Long> menuIds = roleMenuMapper.selectByRoleId(roleId).stream()
                .map(rm -> rm.getMenuId())
                .toList();
        return listByIds(menuIds);
    }

    /**
     * 查询角色集合授予的菜单(用户菜单下发用)。
     *
     * @param roleIds 角色主键集合
     * @return 去重后的菜单列表;集合为空时返回空列表
     */
    public List<Menu> listByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> menuIds = roleMenuMapper.selectByRoleIds(roleIds).stream()
                .map(rm -> rm.getMenuId())
                .distinct()
                .toList();
        return listByIds(menuIds);
    }

    /**
     * 整体覆盖角色的菜单授予,自带事务。
     * <p>先逻辑删除原有关联再批量写入,随后抬升该域权限代次。</p>
     *
     * @param realmId 身份域主键
     * @param roleId 角色主键
     * @param menuIds 目标菜单主键,为空表示清空
     */
    @Transactional
    public void replaceRoleMenus(Long realmId, Long roleId, List<Long> menuIds) {
        roleMenuMapper.softDeleteByRoleId(roleId);
        if (menuIds != null && !menuIds.isEmpty()) {
            roleMenuMapper.insertBatch(realmId, roleId, menuIds);
        }
        // 菜单授予变化会改变"角色-菜单派生"权限,该域权限缓存立即失效
        permissionEpoch.bump(realmId);
    }

    /**
     * 取用户在某应用下被授予且启用中的菜单。
     * <p>只返回属于该应用的节点,父节点未授予时由调用方按树裁剪。</p>
     *
     * @param realmId 身份域主键
     * @param roleIds 用户有效角色主键
     * @param appServiceId 应用服务主键
     * @return 已授予且启用的菜单列表
     */
    public List<Menu> listGranted(Long realmId, List<Long> roleIds, Long appServiceId) {
        return listByRoleIds(roleIds).stream()
                .filter(menu -> realmId.equals(menu.getRealmId()))
                .filter(IamMenuManager::enabled)
                .filter(menu -> appServiceId.equals(menu.getAppServiceId()))
                .toList();
    }

    /**
     * 从菜单集合中收集以 rootId 为根的子树主键(含自身)。
     *
     * @param all 域内全部菜单
     * @param rootId 子树根主键
     * @return 子树菜单主键
     */
    private static List<Long> collectSubtree(List<Menu> all, Long rootId) {
        Set<Long> result = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (current == null || !result.add(current)) {
                continue;
            }
            for (Menu menu : all) {
                if (current.equals(menu.getParentId())) {
                    queue.add(menu.getId());
                }
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 菜单是否启用。
     *
     * @param menu 菜单
     * @return true 表示启用;字段为 null 时按未启用处理
     */
    private static boolean enabled(Menu menu) {
        return Boolean.TRUE.equals(menu.getEnabled());
    }
}
