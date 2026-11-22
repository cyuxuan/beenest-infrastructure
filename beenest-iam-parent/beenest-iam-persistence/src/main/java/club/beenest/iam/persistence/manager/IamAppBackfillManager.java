package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.entity.RoleMenu;
import club.beenest.iam.persistence.entity.RolePermission;
import club.beenest.iam.persistence.mapper.ApplicationServiceMapper;
import club.beenest.iam.persistence.mapper.MenuMapper;
import club.beenest.iam.persistence.mapper.PermissionMapper;
import club.beenest.iam.persistence.mapper.RoleMapper;
import club.beenest.iam.persistence.mapper.RoleMenuMapper;
import club.beenest.iam.persistence.mapper.RolePermissionMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 应用权限回填(存量应用切换 SCOPED 令牌档的前置工具)。
 * <p>SCOPED 令牌只输出绑定应用的权限与菜单:应用视图下平台级权限
 * ({@code app_service_id IS NULL})与平台级菜单都会被裁掉。回填把这些"应用实际在用、
 * 但仍挂在平台命名空间"的资源迁到应用名下。</p>
 * <p>两种策略:</p>
 * <ul>
 *   <li><b>归属迁移(MOVE)</b>:平台级资源直接改归属。授权引用仍指向同一行,幂等可重跑;
 *       其它应用的 SCOPED 令牌本就不含平台资源,迁移不会让它们丢失任何东西。
 *       仅适用于本应用自己的角色或菜单引用的资源;域角色持有的平台资源对全域可见,
 *       改归属会让其它应用的 SCOPED 令牌彻底失去它,这类资源只能复制。</li>
 *   <li><b>复制到应用(COPY)</b>:在应用下建同码副本,并把本应用的引用改指向副本;
 *       用于保留平台原件、或资源已属于其它应用时。</li>
 * </ul>
 */
@Component
public class IamAppBackfillManager {

    /** 候选类型:权限 */
    public static final String KIND_PERMISSION = "PERMISSION";
    /** 候选类型:菜单 */
    public static final String KIND_MENU = "MENU";
    /** 建议策略:归属迁移(平台级资源) */
    public static final String STRATEGY_MOVE = "MOVE";
    /** 建议策略:复制到应用 */
    public static final String STRATEGY_COPY = "COPY";

    private final PermissionMapper permissionMapper;
    private final MenuMapper menuMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final ApplicationServiceMapper applicationServiceMapper;

    public IamAppBackfillManager(PermissionMapper permissionMapper, MenuMapper menuMapper,
                                 RoleMapper roleMapper, RolePermissionMapper rolePermissionMapper,
                                 RoleMenuMapper roleMenuMapper,
                                 ApplicationServiceMapper applicationServiceMapper) {
        this.permissionMapper = permissionMapper;
        this.menuMapper = menuMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.applicationServiceMapper = applicationServiceMapper;
    }

    /**
     * 回填候选资源。
     *
     * @param kind          资源类型(PERMISSION / MENU)
     * @param id            资源主键
     * @param code          资源编码
     * @param name          资源名称
     * @param platform      当前是否平台级(未归属任何应用)
     * @param ownerAppId    当前归属应用(平台级为空)
     * @param ownerAppCode  当前归属应用编码
     * @param strategy      建议策略(MOVE / COPY)
     * @param reasons       来源说明
     * @param roleCodes     引用该资源的应用侧角色编码
     * @param realmRoleCodes 引用该资源的域角色编码
     * @param sharedAppCodes 同样在用该资源的其它应用编码
     */
    public record BackfillCandidate(
            String kind,
            Long id,
            String code,
            String name,
            boolean platform,
            Long ownerAppId,
            String ownerAppCode,
            String strategy,
            List<String> reasons,
            List<String> roleCodes,
            List<String> realmRoleCodes,
            List<String> sharedAppCodes
    ) {
    }

    /**
     * 回填分析结果。
     *
     * @param permissions 待回填的权限候选
     * @param menus       待回填的菜单候选
     */
    public record BackfillAnalysis(List<BackfillCandidate> permissions, List<BackfillCandidate> menus) {
    }

    /**
     * 回填执行结果。
     *
     * @param movedPermissions 迁移归属的权限数
     * @param movedMenus       迁移归属的菜单数
     * @param copiedPermissions 复制到应用的权限数
     * @param copiedMenus       复制到应用的菜单数
     * @param relinkedGrants    改指向或追加的授权引用数
     * @param conflicts         跳过的冲突说明
     */
    public record BackfillOutcome(
            int movedPermissions,
            int movedMenus,
            int copiedPermissions,
            int copiedMenus,
            int relinkedGrants,
            List<String> conflicts
    ) {
    }

    /**
     * 分析应用在 SCOPED 令牌下会缺失的资源。
     * <p>候选来自三条路径:应用侧角色直接授予、应用拥有的菜单挂载、跨应用角色授予的菜单;
     * 域角色授予的资源单列,提示回填后由哪个命名空间承载。</p>
     *
     * @param realmId 域主键
     * @param appId   应用主键
     * @return 权限与菜单候选集合
     */
    public BackfillAnalysis analyze(Long realmId, Long appId) {
        List<Role> allRoles = roleMapper.selectAllByRealmId(realmId);
        Map<Long, Role> roleById = allRoles.stream()
                .collect(Collectors.toMap(Role::getId, Function.identity(), (a, b) -> a));
        List<Role> appRoles = allRoles.stream()
                .filter(role -> appId.equals(role.getAppServiceId()))
                .toList();
        List<Role> realmRoles = allRoles.stream()
                .filter(role -> role.getAppServiceId() == null)
                .toList();

        List<Permission> permissions = permissionMapper.selectByRealmId(realmId);
        Map<Long, Permission> permissionById = permissions.stream()
                .collect(Collectors.toMap(Permission::getId, Function.identity(), (a, b) -> a));
        List<Menu> menus = menuMapper.selectByRealmId(realmId);
        Map<Long, Menu> menuById = menus.stream()
                .collect(Collectors.toMap(Menu::getId, Function.identity(), (a, b) -> a));

        // 1. 候选菜单:应用侧角色授予的、域角色授予的,以及它们的祖先
        Map<Long, Set<String>> menuReasons = new LinkedHashMap<>();
        Map<Long, Set<String>> menuRoleCodes = new LinkedHashMap<>();
        Map<Long, Set<String>> menuRealmRoleCodes = new LinkedHashMap<>();
        collectMenuCandidates(roleMenuMapper.selectByRoleIds(ids(appRoles)), roleById, appId, menuReasons,
                menuRoleCodes, menuRealmRoleCodes, "角色授予");
        collectMenuCandidates(roleMenuMapper.selectByRoleIds(ids(realmRoles)), roleById, appId, menuReasons,
                menuRoleCodes, menuRealmRoleCodes, "域角色授予");
        for (Long menuId : new ArrayList<>(menuReasons.keySet())) {
            collectAncestorReasons(menuId, menuById, appId, menuReasons);
        }
        // 应用自有的菜单已经在本应用命名空间内,SCOPED 令牌会正常输出,不算缺口
        menuReasons.keySet().removeIf(menuId -> {
            Menu menu = menuById.get(menuId);
            return menu != null && appId.equals(menu.getAppServiceId());
        });

        // 2. 候选权限:应用侧角色直授、域角色直授、菜单派生
        Map<Long, Set<String>> permissionReasons = new LinkedHashMap<>();
        Map<Long, Set<String>> permissionRoleCodes = new LinkedHashMap<>();
        Map<Long, Set<String>> permissionRealmRoleCodes = new LinkedHashMap<>();
        collectPermissionCandidates(rolePermissionMapper.selectByRoleIds(ids(appRoles)), roleById, permissionById,
                appId, permissionReasons, permissionRoleCodes, permissionRealmRoleCodes, "角色直授");
        collectPermissionCandidates(rolePermissionMapper.selectByRoleIds(ids(realmRoles)), roleById, permissionById,
                appId, permissionReasons, permissionRoleCodes, permissionRealmRoleCodes, "域角色授予");
        for (Menu menu : menus) {
            if (menu.getPermissionId() == null) {
                continue;
            }
            boolean owned = appId.equals(menu.getAppServiceId());
            boolean migrating = menuReasons.containsKey(menu.getId());
            if (!owned && !migrating) {
                continue;
            }
            Permission permission = permissionById.get(menu.getPermissionId());
            if (permission == null || appId.equals(permission.getAppServiceId())) {
                continue;
            }
            permissionReasons.computeIfAbsent(permission.getId(), key -> new LinkedHashSet<>())
                    .add(owned ? "菜单派生" : "菜单派生(随菜单迁移)");
        }

        // 3. 共享判定:同一资源还被哪些其它应用使用
        List<RolePermission> permissionGrants = rolePermissionMapper
                .selectByPermissionIds(List.copyOf(permissionReasons.keySet()));
        List<RoleMenu> menuGrants = roleMenuMapper.selectByMenuIds(List.copyOf(menuReasons.keySet()));
        Map<Long, Set<Long>> permissionSharedApps = sharedAppsOfPermissions(permissionReasons.keySet(), menus,
                roleById, permissionGrants, roleMenuMapper, appId);
        Map<Long, Set<Long>> menuSharedApps = sharedAppsOfMenus(menuReasons.keySet(), roleById, menuGrants, appId);

        // 4. 剔除已经回填完的候选:应用下已有同码副本,且持有平台原件的角色都持有副本
        BackfillState state = new BackfillState(realmId, appId, appRoles, realmRoles, permissionGrants, menuGrants);
        permissionReasons.keySet().removeIf(id -> state.permissionSatisfied(permissionById.get(id)));
        menuReasons.keySet().removeIf(id -> state.menuSatisfied(menuById.get(id)));

        Map<Long, String> appCodes = appCodes(realmId);
        List<BackfillCandidate> permissionCandidates = permissionReasons.keySet().stream()
                .map(permissionById::get)
                .filter(Objects::nonNull)
                .map(permission -> candidate(KIND_PERMISSION, permission.getId(), permission.getPermissionCode(),
                        permission.getPermissionName(), permission.getAppServiceId(), appCodes,
                        permissionReasons.get(permission.getId()), permissionRoleCodes.get(permission.getId()),
                        permissionRealmRoleCodes.get(permission.getId()),
                        permissionSharedApps.getOrDefault(permission.getId(), Set.of())))
                .toList();
        List<BackfillCandidate> menuCandidates = menuReasons.keySet().stream()
                .map(menuById::get)
                .filter(Objects::nonNull)
                .map(menu -> candidate(KIND_MENU, menu.getId(), menu.getMenuCode(), menu.getMenuName(),
                        menu.getAppServiceId(), appCodes, menuReasons.get(menu.getId()),
                        menuRoleCodes.get(menu.getId()), menuRealmRoleCodes.get(menu.getId()),
                        menuSharedApps.getOrDefault(menu.getId(), Set.of())))
                .toList();
        return new BackfillAnalysis(permissionCandidates, menuCandidates);
    }

    /**
     * 归属迁移:把平台级权限与菜单改到应用名下。
     * <p>只处理仍处于平台命名空间、且未被域角色持有的行,重复执行不产生额外变更;
     * 菜单连同祖先一起迁移。被跳过的资源计入 conflicts。</p>
     *
     * @param realmId       域主键
     * @param appId         目标应用主键
     * @param permissionIds 待迁移的权限主键集合
     * @param menuIds       待迁移的菜单主键集合
     * @return 迁移结果,含迁移计数与冲突说明
     */
    @Transactional
    public BackfillOutcome moveToApp(Long realmId, Long appId, List<Long> permissionIds, List<Long> menuIds) {
        List<String> conflicts = new ArrayList<>();
        Map<Long, Permission> permissionById = permissionMapper.selectByIds(idsOf(permissionIds)).stream()
                .collect(Collectors.toMap(Permission::getId, Function.identity(), (a, b) -> a));
        Map<Long, Menu> menuById = menuMapper.selectByRealmId(realmId).stream()
                .collect(Collectors.toMap(Menu::getId, Function.identity(), (a, b) -> a));

        // 域角色持有的资源对全域可见,改归属会让其它应用的 SCOPED 令牌彻底失去它
        Set<Long> realmHeldPermissions = realmHeldPermissionIds(realmId);
        Set<Long> realmHeldMenus = realmHeldMenuIds(realmId);

        // 菜单连同祖先一起迁移,避免迁出后子菜单变成孤儿;只跟随本次选中的菜单
        Map<Long, Menu> menusToMove = new LinkedHashMap<>();
        for (Long menuId : menuIds) {
            collectAncestors(menuId, menuById, appId, menusToMove);
        }

        List<Long> movablePermissions = new ArrayList<>();
        for (Long permissionId : permissionIds) {
            Permission permission = permissionById.get(permissionId);
            if (permission == null) {
                conflicts.add("权限不存在: " + permissionId);
                continue;
            }
            if (!realmId.equals(permission.getRealmId())) {
                conflicts.add("权限不属于当前身份域: " + permissionId);
                continue;
            }
            if (appId.equals(permission.getAppServiceId())) {
                continue;
            }
            if (permission.getAppServiceId() != null) {
                conflicts.add("权限已归属其它应用,只能复制: " + permission.getPermissionCode());
                continue;
            }
            if (realmHeldPermissions.contains(permissionId)) {
                conflicts.add("权限由域角色持有,迁移会影响其它应用,请改用复制: "
                        + permission.getPermissionCode());
                continue;
            }
            if (permissionMapper.selectByScope(realmId, appId, permission.getPermissionCode()) != null) {
                conflicts.add("应用下已存在同码权限,跳过: " + permission.getPermissionCode());
                continue;
            }
            movablePermissions.add(permissionId);
        }

        List<Long> movableMenus = new ArrayList<>();
        for (Menu menu : menusToMove.values()) {
            if (!realmId.equals(menu.getRealmId())) {
                conflicts.add("菜单不属于当前身份域: " + menu.getId());
                continue;
            }
            if (appId.equals(menu.getAppServiceId())) {
                continue;
            }
            if (menu.getAppServiceId() != null) {
                conflicts.add("菜单已归属其它应用,只能复制: " + menu.getMenuCode());
                continue;
            }
            if (realmHeldMenus.contains(menu.getId())) {
                conflicts.add("菜单由域角色持有,迁移会影响其它应用,请改用复制: " + menu.getMenuCode());
                continue;
            }
            if (findAppMenu(realmId, appId, menu.getMenuCode()) != null) {
                conflicts.add("应用下已存在同码菜单,跳过: " + menu.getMenuCode());
                continue;
            }
            movableMenus.add(menu.getId());
        }

        int movedPermissions = movablePermissions.isEmpty() ? 0
                : permissionMapper.updateAppServiceIdByIds(realmId, appId, movablePermissions);
        int movedMenus = movableMenus.isEmpty() ? 0
                : menuMapper.updateAppServiceIdByIds(realmId, appId, movableMenus);
        return new BackfillOutcome(movedPermissions, movedMenus, 0, 0, 0, conflicts);
    }

    /**
     * 复制到应用:在应用下建同码副本,并把本应用的引用改指向副本。
     * <p>平台原件保持不变,其它应用的授权引用不受影响;域角色额外追加副本引用,
     * 域角色下的用户在本应用的 SCOPED 令牌里也能拿到这些权限。</p>
     *
     * @param realmId       域主键
     * @param appId         目标应用主键
     * @param permissionIds 待复制的权限主键集合
     * @param menuIds       待复制的菜单主键集合
     * @return 复制结果,含复制计数、改指向的授权引用数与冲突说明
     */
    @Transactional
    public BackfillOutcome copyToApp(Long realmId, Long appId, List<Long> permissionIds, List<Long> menuIds) {
        List<String> conflicts = new ArrayList<>();
        Map<Long, Permission> permissionById = permissionMapper.selectByRealmId(realmId).stream()
                .collect(Collectors.toMap(Permission::getId, Function.identity(), (a, b) -> a));
        Map<Long, Menu> menuById = menuMapper.selectByRealmId(realmId).stream()
                .collect(Collectors.toMap(Menu::getId, Function.identity(), (a, b) -> a));

        // 菜单连同祖先一起复制,保持树的完整性
        Map<Long, Menu> menusToCopy = new LinkedHashMap<>();
        for (Long menuId : menuIds) {
            Menu menu = menuById.get(menuId);
            if (menu == null) {
                conflicts.add("菜单不存在: " + menuId);
                continue;
            }
            collectAncestors(menuId, menuById, appId, menusToCopy);
        }

        // 1. 权限副本
        Set<Long> sourcePermissionIds = new LinkedHashSet<>(idsOf(permissionIds));
        for (Menu menu : menusToCopy.values()) {
            if (menu.getPermissionId() != null) {
                sourcePermissionIds.add(menu.getPermissionId());
            }
        }
        Map<Long, Permission> permissionCopies = new LinkedHashMap<>();
        for (Long permissionId : sourcePermissionIds) {
            Permission source = permissionById.get(permissionId);
            if (source == null) {
                conflicts.add("权限不存在: " + permissionId);
                continue;
            }
            if (appId.equals(source.getAppServiceId())) {
                continue;
            }
            Permission copy = findAppPermission(realmId, appId, source.getPermissionCode());
            if (copy == null) {
                copy = new Permission();
                copy.setRealmId(realmId);
                copy.setAppServiceId(appId);
                copy.setPermissionCode(source.getPermissionCode());
                copy.setPermissionName(source.getPermissionName());
                copy.setResource(source.getResource());
                copy.setAction(source.getAction());
                copy.setDescription(source.getDescription());
                permissionMapper.insert(copy);
            }
            permissionCopies.put(source.getId(), copy);
        }

        // 2. 菜单副本(按深度从浅到深,保证父节点先建)
        Map<Long, Menu> menuCopies = new LinkedHashMap<>();
        List<Menu> orderedMenus = menusToCopy.values().stream()
                .sorted(Comparator.comparingInt(menu -> depth(menu, menuById)))
                .toList();
        for (Menu source : orderedMenus) {
            if (appId.equals(source.getAppServiceId())) {
                continue;
            }
            Menu copy = findAppMenu(realmId, appId, source.getMenuCode());
            if (copy == null) {
                copy = new Menu();
                copy.setRealmId(realmId);
                copy.setAppServiceId(appId);
                copy.setMenuCode(source.getMenuCode());
                copy.setMenuName(source.getMenuName());
                copy.setMenuType(source.getMenuType());
                copy.setPath(source.getPath());
                copy.setIcon(source.getIcon());
                copy.setSortOrder(source.getSortOrder());
                copy.setEnabled(source.getEnabled());
                Menu parentCopy = source.getParentId() == null ? null
                        : menuCopies.get(source.getParentId());
                copy.setParentId(parentCopy == null ? null : parentCopy.getId());
                Permission permissionCopy = source.getPermissionId() == null ? null
                        : permissionCopies.get(source.getPermissionId());
                if (permissionCopy != null) {
                    copy.setPermissionId(permissionCopy.getId());
                }
                menuMapper.insert(copy);
            }
            menuCopies.put(source.getId(), copy);
        }

        // 3. 本应用拥有的菜单改挂到权限副本
        int relinked = 0;
        for (Menu menu : menuById.values()) {
            if (!appId.equals(menu.getAppServiceId()) || menu.getPermissionId() == null) {
                continue;
            }
            Permission copy = permissionCopies.get(menu.getPermissionId());
            if (copy == null) {
                continue;
            }
            menuMapper.updatePermissionIdById(menu.getId(), copy.getId());
            relinked++;
        }

        // 4. 应用侧角色改指向副本;域角色追加副本引用
        List<Role> allRoles = roleMapper.selectAllByRealmId(realmId);
        List<Role> appRoles = allRoles.stream()
                .filter(role -> appId.equals(role.getAppServiceId()))
                .toList();
        List<Role> realmRoles = allRoles.stream()
                .filter(role -> role.getAppServiceId() == null)
                .toList();

        relinked += relinkRolePermissions(realmId, appRoles, realmRoles, permissionCopies);
        relinked += relinkRoleMenus(realmId, appRoles, realmRoles, menuCopies);

        return new BackfillOutcome(0, 0, permissionCopies.size(), menuCopies.size(), relinked, conflicts);
    }

    /** 应用侧角色改指向权限副本,域角色追加副本引用。 */
    private int relinkRolePermissions(Long realmId, List<Role> appRoles, List<Role> realmRoles,
                                      Map<Long, Permission> copies) {
        int relinked = 0;
        List<RolePermission> appGrants = rolePermissionMapper.selectByRoleIds(ids(appRoles));
        for (RolePermission grant : appGrants) {
            Permission copy = copies.get(grant.getPermissionId());
            if (copy == null) {
                continue;
            }
            boolean already = rolePermissionMapper.selectByRoleIds(List.of(grant.getRoleId())).stream()
                    .anyMatch(row -> row.getId() != null && !row.getId().equals(grant.getId())
                            && copy.getId().equals(row.getPermissionId()));
            if (already) {
                rolePermissionMapper.softDeleteById(grant.getId());
            } else {
                rolePermissionMapper.updatePermissionIdById(grant.getId(), copy.getId());
            }
            relinked++;
        }
        for (Role role : realmRoles) {
            List<RolePermission> grants = rolePermissionMapper.selectByRoleId(role.getId());
            Set<Long> owned = grants.stream().map(RolePermission::getPermissionId).collect(Collectors.toSet());
            List<Long> missing = copies.entrySet().stream()
                    .filter(entry -> grants.stream().anyMatch(row -> row.getPermissionId().equals(entry.getKey())))
                    .map(entry -> entry.getValue().getId())
                    .filter(permissionId -> !owned.contains(permissionId))
                    .toList();
            if (!missing.isEmpty()) {
                rolePermissionMapper.insertBatch(realmId, role.getId(), missing);
                relinked += missing.size();
            }
        }
        return relinked;
    }

    /** 应用侧角色改指向菜单副本,域角色追加副本引用。 */
    private int relinkRoleMenus(Long realmId, List<Role> appRoles, List<Role> realmRoles,
                                Map<Long, Menu> copies) {
        int relinked = 0;
        List<RoleMenu> appGrants = roleMenuMapper.selectByRoleIds(ids(appRoles));
        for (RoleMenu grant : appGrants) {
            Menu copy = copies.get(grant.getMenuId());
            if (copy == null) {
                continue;
            }
            boolean already = roleMenuMapper.selectByRoleIds(List.of(grant.getRoleId())).stream()
                    .anyMatch(row -> row.getId() != null && !row.getId().equals(grant.getId())
                            && copy.getId().equals(row.getMenuId()));
            if (already) {
                roleMenuMapper.softDeleteById(grant.getId());
            } else {
                roleMenuMapper.updateMenuIdById(grant.getId(), copy.getId());
            }
            relinked++;
        }
        for (Role role : realmRoles) {
            List<RoleMenu> grants = roleMenuMapper.selectByRoleId(role.getId());
            Set<Long> owned = grants.stream().map(RoleMenu::getMenuId).collect(Collectors.toSet());
            List<Long> missing = copies.entrySet().stream()
                    .filter(entry -> grants.stream().anyMatch(row -> row.getMenuId().equals(entry.getKey())))
                    .map(entry -> entry.getValue().getId())
                    .filter(menuId -> !owned.contains(menuId))
                    .toList();
            if (!missing.isEmpty()) {
                roleMenuMapper.insertBatch(realmId, role.getId(), missing);
                relinked += missing.size();
            }
        }
        return relinked;
    }

    private void collectMenuCandidates(List<RoleMenu> grants, Map<Long, Role> roleById, Long appId,
                                       Map<Long, Set<String>> reasons, Map<Long, Set<String>> roleCodes,
                                       Map<Long, Set<String>> realmRoleCodes, String reason) {
        for (RoleMenu grant : grants) {
            Role role = roleById.get(grant.getRoleId());
            if (role == null) {
                continue;
            }
            boolean realmRole = role.getAppServiceId() == null;
            if (!realmRole && !appId.equals(role.getAppServiceId())) {
                continue;
            }
            reasons.computeIfAbsent(grant.getMenuId(), key -> new LinkedHashSet<>()).add(reason);
            if (realmRole) {
                realmRoleCodes.computeIfAbsent(grant.getMenuId(), key -> new LinkedHashSet<>())
                        .add(role.getRoleCode());
            } else {
                roleCodes.computeIfAbsent(grant.getMenuId(), key -> new LinkedHashSet<>())
                        .add(role.getRoleCode());
            }
        }
    }

    private void collectPermissionCandidates(List<RolePermission> grants, Map<Long, Role> roleById,
                                             Map<Long, Permission> permissionById, Long appId,
                                             Map<Long, Set<String>> reasons, Map<Long, Set<String>> roleCodes,
                                             Map<Long, Set<String>> realmRoleCodes, String reason) {
        for (RolePermission grant : grants) {
            Role role = roleById.get(grant.getRoleId());
            Permission permission = permissionById.get(grant.getPermissionId());
            if (role == null || permission == null) {
                continue;
            }
            boolean realmRole = role.getAppServiceId() == null;
            if (!realmRole && !appId.equals(role.getAppServiceId())) {
                continue;
            }
            if (appId.equals(permission.getAppServiceId())) {
                continue;
            }
            reasons.computeIfAbsent(permission.getId(), key -> new LinkedHashSet<>()).add(reason);
            if (realmRole) {
                realmRoleCodes.computeIfAbsent(permission.getId(), key -> new LinkedHashSet<>())
                        .add(role.getRoleCode());
            } else {
                roleCodes.computeIfAbsent(permission.getId(), key -> new LinkedHashSet<>())
                        .add(role.getRoleCode());
            }
        }
    }

    /** 权限还被哪些其它应用使用(菜单引用或角色直授)。 */
    private Map<Long, Set<Long>> sharedAppsOfPermissions(Set<Long> permissionIds, List<Menu> menus,
                                                         Map<Long, Role> roleById, List<RolePermission> permissionGrants,
                                                         RoleMenuMapper roleMenuMapper, Long appId) {
        Map<Long, Set<Long>> shared = new LinkedHashMap<>();
        if (permissionIds.isEmpty()) {
            return shared;
        }
        Set<Long> platformMenuIds = menus.stream()
                .filter(menu -> menu.getPermissionId() != null && permissionIds.contains(menu.getPermissionId()))
                .filter(menu -> menu.getAppServiceId() == null)
                .map(Menu::getId)
                .collect(Collectors.toSet());
        Map<Long, Long> menuPermissionIds = menus.stream()
                .filter(menu -> menu.getPermissionId() != null)
                .collect(Collectors.toMap(Menu::getId, Menu::getPermissionId, (a, b) -> a));
        for (RolePermission grant : permissionGrants) {
            Role role = roleById.get(grant.getRoleId());
            if (role == null || role.getAppServiceId() == null || appId.equals(role.getAppServiceId())) {
                continue;
            }
            shared.computeIfAbsent(grant.getPermissionId(), key -> new LinkedHashSet<>())
                    .add(role.getAppServiceId());
        }
        for (Menu menu : menus) {
            if (menu.getPermissionId() == null || !permissionIds.contains(menu.getPermissionId())) {
                continue;
            }
            if (menu.getAppServiceId() != null && !appId.equals(menu.getAppServiceId())) {
                shared.computeIfAbsent(menu.getPermissionId(), key -> new LinkedHashSet<>())
                        .add(menu.getAppServiceId());
            }
        }
        if (!platformMenuIds.isEmpty()) {
            for (RoleMenu grant : roleMenuMapper.selectByMenuIds(List.copyOf(platformMenuIds))) {
                Role role = roleById.get(grant.getRoleId());
                if (role == null || role.getAppServiceId() == null || appId.equals(role.getAppServiceId())) {
                    continue;
                }
                Long permissionId = menuPermissionIds.get(grant.getMenuId());
                if (permissionId != null) {
                    shared.computeIfAbsent(permissionId, key -> new LinkedHashSet<>())
                            .add(role.getAppServiceId());
                }
            }
        }
        return shared;
    }

    /** 菜单还被哪些其它应用的角色使用。 */
    private Map<Long, Set<Long>> sharedAppsOfMenus(Set<Long> menuIds, Map<Long, Role> roleById,
                                                   List<RoleMenu> grants, Long appId) {
        Map<Long, Set<Long>> shared = new LinkedHashMap<>();
        for (RoleMenu grant : grants) {
            Role role = roleById.get(grant.getRoleId());
            if (role == null || role.getAppServiceId() == null || appId.equals(role.getAppServiceId())) {
                continue;
            }
            shared.computeIfAbsent(grant.getMenuId(), key -> new LinkedHashSet<>()).add(role.getAppServiceId());
        }
        return shared;
    }

    /**
     * 查询域角色持有的权限主键,这部分资源不参与归属迁移。
     *
     * @param realmId 域主键
     * @return 权限主键集合
     */
    private Set<Long> realmHeldPermissionIds(Long realmId) {
        List<Long> realmRoleIds = roleMapper.selectAllByRealmId(realmId).stream()
                .filter(role -> role.getAppServiceId() == null)
                .map(Role::getId)
                .toList();
        if (realmRoleIds.isEmpty()) {
            return Set.of();
        }
        return rolePermissionMapper.selectByRoleIds(realmRoleIds).stream()
                .map(RolePermission::getPermissionId)
                .collect(Collectors.toSet());
    }

    /**
     * 查询域角色持有的菜单主键,这部分资源不参与归属迁移。
     *
     * @param realmId 域主键
     * @return 菜单主键集合
     */
    private Set<Long> realmHeldMenuIds(Long realmId) {
        List<Long> realmRoleIds = roleMapper.selectAllByRealmId(realmId).stream()
                .filter(role -> role.getAppServiceId() == null)
                .map(Role::getId)
                .toList();
        if (realmRoleIds.isEmpty()) {
            return Set.of();
        }
        return roleMenuMapper.selectByRoleIds(realmRoleIds).stream()
                .map(RoleMenu::getMenuId)
                .collect(Collectors.toSet());
    }

    /**
     * 回填完成度判定。
     * <p>应用下已存在同码副本、且持有平台原件的相关角色都持有该副本时,
     * 该资源在 SCOPED 令牌下不缺失,报告不再将其列为缺口。</p>
     */
    private final class BackfillState {
        private final Set<Long> relevantRoleIds;
        private final Map<Long, Set<Long>> permissionHolders;
        private final Map<Long, Set<Long>> menuHolders;
        private final Map<String, Permission> appPermissionByCode;
        private final Map<String, Menu> appMenuByCode;
        private final Map<Long, Set<Long>> appPermissionHolders;
        private final Map<Long, Set<Long>> appMenuHolders;
        private final Set<Long> appMenuPermissionIds;

        /**
         * 装载判定所需的角色集合、资源持有关系与应用侧同码副本索引。
         *
         * @param realmId          域主键
         * @param appId            应用主键
         * @param appRoles         该应用的侧角色
         * @param realmRoles       全域域角色
         * @param permissionGrants 候选权限的角色授予记录
         * @param menuGrants       候选菜单的角色授予记录
         */
        BackfillState(Long realmId, Long appId, List<Role> appRoles, List<Role> realmRoles,
                      List<RolePermission> permissionGrants, List<RoleMenu> menuGrants) {
            this.relevantRoleIds = Stream.concat(appRoles.stream(), realmRoles.stream())
                    .map(Role::getId)
                    .collect(Collectors.toSet());
            this.permissionHolders = permissionRoleHolders(permissionGrants);
            this.menuHolders = menuRoleHolders(menuGrants);
            Permission condition = new Permission();
            condition.setAppServiceId(appId);
            this.appPermissionByCode = permissionMapper.selectByCondition(realmId, condition).stream()
                    .collect(Collectors.toMap(Permission::getPermissionCode, Function.identity(), (a, b) -> a));
            this.appMenuByCode = menuMapper.selectByAppServiceId(realmId, appId).stream()
                    .collect(Collectors.toMap(Menu::getMenuCode, Function.identity(), (a, b) -> a));
            this.appPermissionHolders = rolePermissionMapper.selectByPermissionIds(
                            appPermissionByCode.values().stream().map(Permission::getId).toList()).stream()
                    .collect(Collectors.groupingBy(RolePermission::getPermissionId,
                            Collectors.mapping(RolePermission::getRoleId, Collectors.toSet())));
            this.appMenuHolders = roleMenuMapper.selectByMenuIds(
                            appMenuByCode.values().stream().map(Menu::getId).toList()).stream()
                    .collect(Collectors.groupingBy(RoleMenu::getMenuId,
                            Collectors.mapping(RoleMenu::getRoleId, Collectors.toSet())));
            this.appMenuPermissionIds = appMenuByCode.values().stream()
                    .map(Menu::getPermissionId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }

        /**
         * 判定权限缺口是否已被回填补齐。
         * <p>应用自有菜单仍挂平台原件时,派生权限依旧被裁掉,视为未补齐。</p>
         *
         * @param permission 待判定的平台权限,可为 null
         * @return true 表示应用命名空间内已有等效副本
         */
        boolean permissionSatisfied(Permission permission) {
            if (permission == null) {
                return false;
            }
            Permission copy = appPermissionByCode.get(permission.getPermissionCode());
            if (copy == null) {
                return false;
            }
            // 应用自有菜单仍挂平台原件,派生权限依旧被裁掉
            if (appMenuPermissionIds.contains(permission.getId())) {
                return false;
            }
            Set<Long> copyHolders = appPermissionHolders.getOrDefault(copy.getId(), Set.of());
            for (Long roleId : permissionHolders.getOrDefault(permission.getId(), Set.of())) {
                if (relevantRoleIds.contains(roleId) && !copyHolders.contains(roleId)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 判定菜单缺口是否已被回填补齐。
         *
         * @param menu 待判定的平台菜单,可为 null
         * @return true 表示应用命名空间内已有同码副本且被相关角色全部持有
         */
        boolean menuSatisfied(Menu menu) {
            if (menu == null) {
                return false;
            }
            Menu copy = appMenuByCode.get(menu.getMenuCode());
            if (copy == null) {
                return false;
            }
            Set<Long> copyHolders = appMenuHolders.getOrDefault(copy.getId(), Set.of());
            for (Long roleId : menuHolders.getOrDefault(menu.getId(), Set.of())) {
                if (relevantRoleIds.contains(roleId) && !copyHolders.contains(roleId)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 按权限聚合持有它的角色主键。
     *
     * @param grants 角色-权限授予记录
     * @return 权限主键到角色主键集合的映射
     */
    private static Map<Long, Set<Long>> permissionRoleHolders(List<RolePermission> grants) {
        return grants.stream().collect(Collectors.groupingBy(RolePermission::getPermissionId,
                Collectors.mapping(RolePermission::getRoleId, Collectors.toSet())));
    }

    /**
     * 按菜单聚合持有它的角色主键。
     *
     * @param grants 角色-菜单授予记录
     * @return 菜单主键到角色主键集合的映射
     */
    private static Map<Long, Set<Long>> menuRoleHolders(List<RoleMenu> grants) {
        return grants.stream().collect(Collectors.groupingBy(RoleMenu::getMenuId,
                Collectors.mapping(RoleMenu::getRoleId, Collectors.toSet())));
    }

    /**
     * 装配回填候选。
     * <p>平台资源被域角色持有时只能复制:域角色对全域可见,改归属会让其它应用的
     * SCOPED 令牌失去该资源;无域角色持有的平台资源按迁移处理。</p>
     *
     * @param kind          资源类型(PERMISSION / MENU)
     * @param id            资源主键
     * @param code          资源编码
     * @param name          资源名称
     * @param ownerAppId    当前归属应用主键,为空表示平台命名空间
     * @param appCodes      域内应用主键到应用编码的映射
     * @param reasons       来源说明
     * @param roleCodes     引用该资源的应用侧角色编码
     * @param realmRoleCodes 引用该资源的域角色编码
     * @param sharedAppIds  同样在用该资源的其它应用主键
     * @return 回填候选
     */
    private BackfillCandidate candidate(String kind, Long id, String code, String name, Long ownerAppId,
                                         Map<Long, String> appCodes, Set<String> reasons, Set<String> roleCodes,
                                         Set<String> realmRoleCodes, Set<Long> sharedAppIds) {
        boolean platform = ownerAppId == null;
        boolean realmOwned = realmRoleCodes != null && !realmRoleCodes.isEmpty();
        return new BackfillCandidate(kind, id, code, name, platform, ownerAppId,
                ownerAppId == null ? null : appCodes.get(ownerAppId),
                platform && !realmOwned ? STRATEGY_MOVE : STRATEGY_COPY,
                List.copyOf(reasons == null ? Set.of() : reasons),
                List.copyOf(roleCodes == null ? Set.of() : roleCodes),
                List.copyOf(realmRoleCodes == null ? Set.of() : realmRoleCodes),
                sharedAppIds.stream().map(appCodes::get).filter(Objects::nonNull).sorted().toList());
    }

    /**
     * 沿 parent_id 上溯收集不属于当前应用的菜单祖先,写入待迁移集合。
     *
     * @param menuId    起始菜单主键
     * @param menuById  域内菜单索引
     * @param appId     当前应用主键
     * @param target    待迁移菜单集合,已存在的菜单不再上溯
     */
    private void collectAncestors(Long menuId, Map<Long, Menu> menuById, Long appId, Map<Long, Menu> target) {
        Menu menu = menuById.get(menuId);
        if (menu == null || target.containsKey(menuId)) {
            return;
        }
        if (!appId.equals(menu.getAppServiceId())) {
            target.put(menuId, menu);
        }
        if (menu.getParentId() != null) {
            collectAncestors(menu.getParentId(), menuById, appId, target);
        }
    }

    /**
     * 沿 parent_id 上溯为菜单祖先补充候选来源,与迁移口径一致:子菜单迁出后父级同批处理。
     *
     * @param menuId   起始菜单主键
     * @param menuById 域内菜单索引
     * @param appId    当前应用主键
     * @param reasons  菜单主键到来源说明集合的映射,已有记录的菜单不再上溯
     */
    private void collectAncestorReasons(Long menuId, Map<Long, Menu> menuById, Long appId,
                                        Map<Long, Set<String>> reasons) {
        Menu menu = menuById.get(menuId);
        if (menu == null || reasons.containsKey(menuId)) {
            return;
        }
        if (!appId.equals(menu.getAppServiceId())) {
            reasons.computeIfAbsent(menuId, key -> new LinkedHashSet<>()).add("父菜单跟随");
        }
        if (menu.getParentId() != null) {
            collectAncestorReasons(menu.getParentId(), menuById, appId, reasons);
        }
    }

    /**
     * 计算菜单在树上的层级深度,上溯最多 32 层以规避环状 parent_id。
     *
     * @param menu     起始菜单
     * @param menuById 域内菜单索引
     * @return 祖先层数;父级缺失或超过上限时返回已遍历的层数
     */
    private static int depth(Menu menu, Map<Long, Menu> menuById) {
        int depth = 0;
        Long parentId = menu.getParentId();
        while (parentId != null && depth < 32) {
            Menu parent = menuById.get(parentId);
            if (parent == null) {
                break;
            }
            depth++;
            parentId = parent.getParentId();
        }
        return depth;
    }

    /**
     * 查询应用命名空间内的同码权限。
     *
     * @param realmId        域主键
     * @param appId          应用主键
     * @param permissionCode 权限编码
     * @return 权限,不存在时返回 null
     */
    private Permission findAppPermission(Long realmId, Long appId, String permissionCode) {
        return permissionMapper.selectByScope(realmId, appId, permissionCode);
    }

    /**
     * 查询应用命名空间内的同码菜单。
     *
     * @param realmId  域主键
     * @param appId    应用主键
     * @param menuCode 菜单编码
     * @return 菜单,不存在时返回 null
     */
    private Menu findAppMenu(Long realmId, Long appId, String menuCode) {
        return menuMapper.selectByAppServiceId(realmId, appId).stream()
                .filter(menu -> menuCode.equals(menu.getMenuCode()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 查询域内应用主键到应用编码的映射,用于候选中展示归属与共享应用。
     *
     * @param realmId 域主键
     * @return 应用主键到应用编码的映射
     */
    private Map<Long, String> appCodes(Long realmId) {
        return applicationServiceMapper.selectByRealmId(realmId).stream()
                .collect(Collectors.toMap(ApplicationService::getId, ApplicationService::getAppCode, (a, b) -> a));
    }

    /**
     * 提取角色主键列表。
     *
     * @param roles 角色列表
     * @return 角色主键列表
     */
    private static List<Long> ids(List<Role> roles) {
        return roles.stream().map(Role::getId).toList();
    }

    /**
     * 清洗外部传入的主键集合:剔除空值并去重。
     *
     * @param ids 主键集合,可为 null
     * @return 可用于 IN 查询的主键列表
     */
    private static List<Long> idsOf(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }
}
