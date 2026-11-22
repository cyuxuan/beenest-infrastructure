package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.spi.provider.PermissionProvider;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.entity.RoleMenu;
import club.beenest.iam.persistence.entity.RolePermission;
import club.beenest.iam.persistence.entity.UserPermission;
import club.beenest.iam.persistence.mapper.ApplicationServiceMapper;
import club.beenest.iam.persistence.mapper.MenuMapper;
import club.beenest.iam.persistence.mapper.PermissionMapper;
import club.beenest.iam.persistence.mapper.RoleMenuMapper;
import club.beenest.iam.persistence.mapper.RolePermissionMapper;
import club.beenest.iam.persistence.mapper.UserPermissionMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限计算实现(组织权限公式)。
 * <p>最终权限 = (角色-菜单派生 ∪ 角色直接 ∪ 组角色(含菜单派生) ∪ 用户 GRANT) − 用户 REVOKE;
 * 各表均为单表查询,关联在内存中拼装。组角色与用户角色等价参与计算,
 * 支持按组织架构(部门)批量授权。</p>
 */
@Component
public class IamPermissionManager implements PermissionProvider {

    private static final Logger log = LoggerFactory.getLogger(IamPermissionManager.class);

    /** 权限缓存兜底有效期:正常路径靠代次失效,TTL 只防止漏改时长期留存旧值 */
    private static final Duration PERMISSION_CACHE_TTL = Duration.ofMinutes(5);

    /**
     * 权限诊断结果:保留各来源明细,最终权限与令牌签发使用同一套求值逻辑。
     *
     * @param roleDirectPermissions    角色直接授予的权限
     * @param menuDerivedPermissions   角色经菜单授予派生的权限
     * @param userGrantedPermissions   生效的用户特批授予权限
     * @param userRevokedPermissions   生效的用户特批撤销权限
     * @param effectivePermissions     求值后的最终权限
     */
    public record PermissionInspection(
            List<Permission> roleDirectPermissions,
            List<Permission> menuDerivedPermissions,
            List<Permission> userGrantedPermissions,
            List<Permission> userRevokedPermissions,
            List<Permission> effectivePermissions
    ) {
    }

    private final IamEffectiveRoleResolver effectiveRoleResolver;
    private final RoleMenuMapper roleMenuMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserPermissionMapper userPermissionMapper;
    private final MenuMapper menuMapper;
    private final PermissionMapper permissionMapper;
    private final ApplicationServiceMapper applicationServiceMapper;
    /** 权限缓存代次:变更时自增,整域缓存立即失效 */
    private final IamPermissionEpoch permissionEpoch;
    private final RedisUtils redisUtils;

    public IamPermissionManager(IamEffectiveRoleResolver effectiveRoleResolver,
                                RoleMenuMapper roleMenuMapper, RolePermissionMapper rolePermissionMapper,
                                UserPermissionMapper userPermissionMapper, MenuMapper menuMapper,
                                PermissionMapper permissionMapper,
                                ApplicationServiceMapper applicationServiceMapper,
                                IamPermissionEpoch permissionEpoch,
                                RedisUtils redisUtils) {
        this.effectiveRoleResolver = effectiveRoleResolver;
        this.roleMenuMapper = roleMenuMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userPermissionMapper = userPermissionMapper;
        this.menuMapper = menuMapper;
        this.permissionMapper = permissionMapper;
        this.applicationServiceMapper = applicationServiceMapper;
        this.permissionEpoch = permissionEpoch;
        this.redisUtils = redisUtils;
    }

    @Override
    public long currentPermissionEpoch(Long realmId) {
        return permissionEpoch.current(realmId);
    }

    @Override
    public Set<String> computePermissionCodes(Long realmId, Long userId) {
        return computeCached(realmId, userId, null);
    }

    @Override
    public Set<String> computeAppPermissionCodes(Long realmId, Long userId, Long appServiceId) {
        return appServiceId == null ? Set.of() : computeCached(realmId, userId, appServiceId);
    }

    /**
     * 带缓存的权限求值。
     * <p>缓存键含权限代次:任何影响权限的变更都会让代次自增(见 {@link IamPermissionEpoch}),
     * 旧代次条目不再被命中,撤销因此立即生效。TTL 只作兜底。</p>
     *
     * @param realmId      域主键
     * @param userId       用户主键
     * @param appServiceId 应用服务主键,为空表示全域权限
     * @return 权限编码集合
     */
    private Set<String> computeCached(Long realmId, Long userId, Long appServiceId) {
        long epoch = permissionEpoch.current(realmId);
        String key = appServiceId == null
                ? String.format(IamRedisKeyConstants.PERMISSION_CACHE, realmId, userId, epoch)
                : String.format(IamRedisKeyConstants.APP_PERMISSION_CACHE, realmId, userId, appServiceId, epoch);
        Set<String> cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        Set<String> codes = compute(realmId, userId, appServiceId);
        writeCache(key, codes);
        return codes;
    }

    /**
     * 读取权限缓存。
     *
     * @param key 缓存键
     * @return 缓存中的权限编码集合;未命中、内容为空或读取失败时返回 null,由调用方退回实时求值
     */
    private Set<String> readCache(String key) {
        try {
            List<String> values = redisUtils.getSetAsList(key);
            return values.isEmpty() ? null : new HashSet<>(values);
        } catch (RuntimeException e) {
            log.debug("权限缓存读取失败,退回实时求值: key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 写入权限缓存。
     * <p>写入失败只记日志,不影响本次求值结果。</p>
     *
     * @param key   缓存键
     * @param codes 权限编码集合
     */
    private void writeCache(String key, Set<String> codes) {
        try {
            redisUtils.addToSetWithTtl(key, PERMISSION_CACHE_TTL, codes.toArray(new String[0]));
        } catch (RuntimeException e) {
            log.debug("权限缓存写入失败: key={}, error={}", key, e.getMessage());
        }
    }

    /**
     * 按应用命名空间计算权限编码。
     * <p>应用视图下,角色直接权限、菜单派生权限与用户 GRANT 最终都要过
     * permission.app_service_id 过滤,平台权限不进入业务应用令牌。</p>
     *
     * @param realmId      域主键
     * @param userId       用户主键
     * @param appServiceId 应用服务主键,为空时返回全域权限(含平台权限)
     * @return 权限编码集合
     */
    private Set<String> compute(Long realmId, Long userId, Long appServiceId) {
        return inspect(realmId, userId, appServiceId).effectivePermissions().stream()
                .map(Permission::getPermissionCode)
                .collect(Collectors.toSet());
    }

    /**
     * 计算权限并保留各来源明细,供控制台诊断权限来源。
     * <p>与令牌签发、鉴权使用同一求值路径,保证诊断结果与实际权限一致。
     * 求值流程:有效角色(直授 + 组继承,本域启用中)→ 角色直接权限 →
     * 角色-菜单派生权限 → 用户特批 GRANT/REVOKE(过期记录不参与)→
     * (直接 ∪ 菜单派生 ∪ GRANT) − REVOKE → 按域与应用命名空间过滤装配。</p>
     *
     * @param realmId      域主键
     * @param userId       用户主键
     * @param appServiceId 应用服务主键,为空时按全域求值
     * @return 含来源明细的权限诊断结果
     */
    public PermissionInspection inspect(Long realmId, Long userId, Long appServiceId) {
        // 1. 有效角色(直授 + 组继承,本域启用中)
        List<Long> effectiveRoleIds = effectiveRoleResolver.resolveRoleIds(realmId, userId);
        // 2. 角色直接权限
        Set<Long> directIds = effectiveRoleIds.isEmpty() ? Set.of()
                : rolePermissionMapper.selectByRoleIds(effectiveRoleIds).stream()
                .map(RolePermission::getPermissionId)
                .collect(Collectors.toSet());
        // 3. 角色-菜单派生权限:停用菜单或停用应用下的菜单不再派生,应用视图只取本应用菜单
        Set<Long> menuDerivedIds = menuDerivedPermissionIds(effectiveRoleIds, appServiceId);
        // 4. 有效特批:过期记录不参与求值
        Set<Long> grantIds = new HashSet<>();
        Set<Long> revokeIds = new HashSet<>();
        for (UserPermission userPermission : userPermissionMapper.selectByUserId(userId)) {
            if (!realmId.equals(userPermission.getRealmId()) || !effective(userPermission.getExpiredTime())) {
                continue;
            }
            if ("GRANT".equals(userPermission.getGrantType())) {
                grantIds.add(userPermission.getPermissionId());
            } else if ("REVOKE".equals(userPermission.getGrantType())) {
                revokeIds.add(userPermission.getPermissionId());
            }
        }
        // 5. 最终权限 = (直接 ∪ 菜单派生 ∪ GRANT) − REVOKE
        Set<Long> effectiveIds = new HashSet<>(directIds);
        effectiveIds.addAll(menuDerivedIds);
        effectiveIds.addAll(grantIds);
        effectiveIds.removeAll(revokeIds);
        // 6. 一次性加载涉及的权限,并按域与应用命名空间过滤
        Set<Long> involvedIds = new HashSet<>(directIds);
        involvedIds.addAll(menuDerivedIds);
        involvedIds.addAll(grantIds);
        involvedIds.addAll(revokeIds);
        Map<Long, Permission> permissions = loadPermissions(realmId, appServiceId, involvedIds);
        return new PermissionInspection(
                selectPermissions(permissions, directIds),
                selectPermissions(permissions, menuDerivedIds),
                selectPermissions(permissions, grantIds),
                selectPermissions(permissions, revokeIds),
                selectPermissions(permissions, effectiveIds));
    }

    /**
     * 计算角色经菜单授予派生出的权限主键。
     * <p>停用菜单与停用应用下的菜单不参与派生;应用视图只取本应用的菜单。</p>
     *
     * @param effectiveRoleIds 有效角色主键集合
     * @param appServiceId     应用服务主键,为空时不限定应用
     * @return 派生的权限主键集合
     */
    private Set<Long> menuDerivedPermissionIds(List<Long> effectiveRoleIds, Long appServiceId) {
        if (effectiveRoleIds.isEmpty()) {
            return Set.of();
        }
        List<Long> menuIds = roleMenuMapper.selectByRoleIds(effectiveRoleIds).stream()
                .map(RoleMenu::getMenuId)
                .distinct()
                .toList();
        if (menuIds.isEmpty()) {
            return Set.of();
        }
        List<Menu> menus = menuMapper.selectByIds(menuIds);
        Set<Long> enabledAppIds = enabledAppIds(menus);
        return menus.stream()
                .filter(menu -> Boolean.TRUE.equals(menu.getEnabled()))
                .filter(menu -> menu.getPermissionId() != null)
                .filter(menu -> appServiceId == null
                        ? menu.getAppServiceId() == null || enabledAppIds.contains(menu.getAppServiceId())
                        : appServiceId.equals(menu.getAppServiceId()) && enabledAppIds.contains(appServiceId))
                .map(Menu::getPermissionId)
                .collect(Collectors.toSet());
    }

    /**
     * 查询菜单涉及的启用中应用主键。
     *
     * @param menus 菜单列表
     * @return 其中状态为启用的应用主键集合
     */
    private Set<Long> enabledAppIds(List<Menu> menus) {
        List<Long> appIds = menus.stream()
                .map(Menu::getAppServiceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (appIds.isEmpty()) {
            return Set.of();
        }
        return applicationServiceMapper.selectByIds(appIds).stream()
                .filter(app -> Boolean.TRUE.equals(app.getEnabled()))
                .map(ApplicationService::getId)
                .collect(Collectors.toSet());
    }

    /**
     * 加载权限主键对应的本域权限。
     *
     * @param realmId      域主键,非本域权限被剔除
     * @param appServiceId 应用服务主键,非空时只保留该应用命名空间内的权限
     * @param ids          待加载的权限主键集合
     * @return 权限主键到权限实体的映射
     */
    private Map<Long, Permission> loadPermissions(Long realmId, Long appServiceId, Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return permissionMapper.selectByIds(List.copyOf(ids)).stream()
                .filter(permission -> realmId.equals(permission.getRealmId()))
                .filter(permission -> appServiceId == null || appServiceId.equals(permission.getAppServiceId()))
                .collect(Collectors.toMap(Permission::getId, permission -> permission));
    }

    /**
     * 按权限主键集合装配为稳定顺序的权限列表。
     * <p>排序键为应用主键(空值在前)+ 权限编码。</p>
     *
     * @param permissions 权限主键到实体的映射
     * @param ids         待装配的权限主键集合;映射中不存在的主键被跳过
     * @return 权限列表
     */
    private static List<Permission> selectPermissions(Map<Long, Permission> permissions, Set<Long> ids) {
        return ids.stream()
                .map(permissions::get)
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(Permission::getAppServiceId, Comparator.nullsFirst(Long::compareTo))
                        .thenComparing(Permission::getPermissionCode))
                .toList();
    }

    /**
     * 判定特批记录是否仍在有效期内。
     *
     * @param expiredTime 过期时间;为空表示永久有效
     * @return true 表示参与权限求值
     */
    private static boolean effective(LocalDateTime expiredTime) {
        return expiredTime == null || expiredTime.isAfter(LocalDateTime.now());
    }

    // ===== 管理侧:单表读取 =====

    /**
     * 按主键查询权限。
     *
     * @param id 权限主键
     * @return 权限,不存在时返回 null
     */
    public Permission getById(Long id) {
        return permissionMapper.selectById(id);
    }

    /**
     * 按权限命名空间查询权限。
     *
     * @param realmId        域主键
     * @param appServiceId   应用服务主键,为空表示平台权限
     * @param permissionCode 权限编码
     * @return 权限,不存在时返回 null
     */
    public Permission getByScope(Long realmId, Long appServiceId, String permissionCode) {
        return permissionMapper.selectByScope(realmId, appServiceId, permissionCode);
    }

    /**
     * 查询角色已直接授予的权限(管理侧读模型,不含菜单派生)。
     *
     * @param roleId 角色主键
     * @return 权限列表
     */
    public List<Permission> listByRoleId(Long roleId) {
        List<Long> permissionIds = rolePermissionMapper.selectByRoleId(roleId).stream()
                .map(RolePermission::getPermissionId)
                .toList();
        return permissionIds.isEmpty() ? List.of() : permissionMapper.selectByIds(permissionIds);
    }

    /**
     * 按主键批量查询权限,供写入前校验归属域。
     *
     * @param ids 权限主键集合
     * @return 权限列表;入参为空时返回空列表
     */
    public List<Permission> listByIds(List<Long> ids) {
        return (ids == null || ids.isEmpty()) ? List.of() : permissionMapper.selectByIds(ids);
    }

    /**
     * 查询应用命名空间内的全部权限。
     *
     * @param realmId      域主键
     * @param appServiceId 应用服务主键,为空时返回空列表
     * @return 权限列表
     */
    public List<Permission> listByAppServiceId(Long realmId, Long appServiceId) {
        if (appServiceId == null) {
            return List.of();
        }
        Permission condition = new Permission();
        condition.setAppServiceId(appServiceId);
        return permissionMapper.selectByCondition(realmId, condition);
    }

    /**
     * 新建权限,主键回填,并自增该域权限缓存代次。
     *
     * @param permission 权限实体
     * @return 影响行数
     */
    public int create(Permission permission) {
        int rows = permissionMapper.insert(permission);
        permissionEpoch.bump(permission.getRealmId());
        return rows;
    }

    /**
     * 更新权限名称与说明,并自增该域权限缓存代次。
     *
     * @param permission 权限实体
     * @return 影响行数
     */
    public int update(Permission permission) {
        int rows = permissionMapper.updateById(permission);
        permissionEpoch.bump(permission.getRealmId());
        return rows;
    }

    /**
     * 逻辑删除权限,并自增其所属域的权限缓存代次。
     * <p>删除前先读记录取域标识,代次按域自增。</p>
     *
     * @param id 权限主键
     * @return 影响行数
     */
    public int remove(Long id) {
        Permission existing = permissionMapper.selectById(id);
        int rows = permissionMapper.softDeleteById(id);
        permissionEpoch.bump(existing == null ? null : existing.getRealmId());
        return rows;
    }

    /**
     * 判定权限是否仍被角色、菜单或用户特批引用。
     *
     * @param permissionId 权限主键
     * @return true 表示存在引用,不可删除
     */
    public boolean isInUse(Long permissionId) {
        return rolePermissionMapper.countByPermissionId(permissionId) > 0
                || menuMapper.countByPermissionId(permissionId) > 0
                || userPermissionMapper.countByPermissionId(permissionId) > 0;
    }

    /**
     * 整体覆盖角色的直接权限。
     * <p>先逻辑删除原有直接关联再批量写入;菜单派生权限不受影响。
     * 两步写同批提交,中途失败时回滚原关联。</p>
     * <p>完成后自增该域权限缓存代次。</p>
     *
     * @param realmId       域主键
     * @param roleId        角色主键
     * @param permissionIds 权限主键集合,为空表示清空直接权限
     */
    @Transactional
    public void replaceRolePermissions(Long realmId, Long roleId, List<Long> permissionIds) {
        rolePermissionMapper.softDeleteByRoleId(roleId);
        if (permissionIds != null && !permissionIds.isEmpty()) {
            rolePermissionMapper.insertBatch(realmId, roleId, permissionIds);
        }
        permissionEpoch.bump(realmId);
    }

    /**
     * 分页查询域内权限。
     *
     * @param realmId   域主键
     * @param condition 查询条件
     * @param pageNum   页码,从 1 开始
     * @param pageSize  每页条数
     * @return 分页结果
     */
    public PageResult<Permission> page(Long realmId, Permission condition, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Permission> list = permissionMapper.selectByCondition(realmId, condition);
        PageInfo<Permission> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }

    /**
     * 分页查询用户特批可选的权限。
     *
     * @param realmId      域主键
     * @param appServiceId 应用服务主键,为空表示不限定应用
     * @param keyword      权限编码/名称关键词,为空表示不过滤
     * @param pageNum      页码,从 1 开始
     * @param pageSize     每页条数
     * @return 分页结果
     */
    public PageResult<Permission> pageForOverride(Long realmId, Long appServiceId, String keyword,
                                                  int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Permission> list = permissionMapper.selectForOverride(realmId, appServiceId, keyword);
        PageInfo<Permission> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }
}
