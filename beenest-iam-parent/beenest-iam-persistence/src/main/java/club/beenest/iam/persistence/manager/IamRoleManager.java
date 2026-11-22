package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.core.model.RoleModel;
import club.beenest.iam.core.spi.provider.RoleProvider;
import club.beenest.iam.persistence.converter.RoleConverter;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.entity.UserRole;
import club.beenest.iam.persistence.mapper.RoleMapper;
import club.beenest.iam.persistence.mapper.UserRoleMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 角色存储实现(iam_role / iam_user_role)。
 * <p>角色按容器类型 REALM / CLIENT / APP 划分作用域,授权面只认 REALM 容器;
 * 角色与用户角色关联的变更自增所属域的权限缓存代次。</p>
 */
@Component
public class IamRoleManager implements RoleProvider {

    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RoleConverter roleConverter;
    private final IamEffectiveRoleResolver effectiveRoleResolver;
    /** 权限缓存代次:角色相关变更后自增,该域权限缓存立即失效 */
    private final IamPermissionEpoch permissionEpoch;

    public IamRoleManager(UserRoleMapper userRoleMapper, RoleMapper roleMapper, RoleConverter roleConverter,
                          IamEffectiveRoleResolver effectiveRoleResolver,
                          IamPermissionEpoch permissionEpoch) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.roleConverter = roleConverter;
        this.effectiveRoleResolver = effectiveRoleResolver;
        this.permissionEpoch = permissionEpoch;
    }

    // ===== 认证授权侧:领域模型读取 =====

    @Override
    public List<RoleModel> findRolesByUserId(Long realmId, Long userId) {
        // 令牌角色与权限计算使用同一有效角色口径(含组继承)
        return effectiveRoleResolver.resolve(realmId, userId).stream()
                .map(roleConverter::toModel)
                .toList();
    }

    @Override
    public Optional<RoleModel> findByCode(Long realmId, String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return Optional.empty();
        }
        // 只认域级角色(REALM 容器):应用角色的授权范围由应用自己管,不进平台授权面
        Role role = roleMapper.selectByScope(realmId, "REALM", null, null, roleCode.trim());
        return role == null ? Optional.empty() : Optional.of(roleConverter.toModel(role));
    }

    @Override
    @Transactional
    public void grantRoles(Long realmId, Long userId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        // 1. 已有角色集合
        Set<Long> current = new HashSet<>();
        for (UserRole userRole : userRoleMapper.selectByUserId(userId)) {
            current.add(userRole.getRoleId());
        }
        // 2. 只补不删:自动授权不能把管理员手工分配的角色顶掉
        boolean changed = false;
        for (Long roleId : roleIds) {
            if (roleId != null && current.add(roleId)) {
                UserRole userRole = new UserRole();
                userRole.setRealmId(realmId);
                userRole.setUserId(userId);
                userRole.setRoleId(roleId);
                // grantedBy 为空表示非人工操作(外部身份自动授权)
                userRole.setGrantedBy(null);
                userRoleMapper.insert(userRole);
                changed = true;
            }
        }
        // 3. 该域权限缓存立即失效:角色变化直接影响权限求值结果
        if (changed) {
            permissionEpoch.bump(realmId);
        }
    }

    // ===== 管理侧:单表 CRUD =====

    /**
     * 按主键查询角色实体。
     *
     * @param id 角色主键
     * @return 角色,不存在时返回 null
     */
    public Role getById(Long id) {
        return roleMapper.selectById(id);
    }

    /**
     * 按作用域查询角色,用于唯一性校验。
     *
     * @param realmId       域主键
     * @param containerType 容器类型(REALM / CLIENT / APP)
     * @param clientId      客户端主键,CLIENT 容器用
     * @param appServiceId  应用服务主键,APP 容器用
     * @param roleCode      角色编码
     * @return 角色,不存在时返回 null
     */
    public Role getByScope(Long realmId, String containerType, Long clientId, Long appServiceId,
                           String roleCode) {
        return roleMapper.selectByScope(realmId, containerType, clientId, appServiceId, roleCode);
    }

    /**
     * 按应用与角色编码查询,用于 APP 角色唯一性校验及历史 CLIENT 角色冲突检查。
     *
     * @param realmId      域主键
     * @param appServiceId 应用服务主键,为空时直接返回 null
     * @param roleCode     角色编码
     * @return 角色,不存在时返回 null
     */
    public Role getByAppAndCode(Long realmId, Long appServiceId, String roleCode) {
        return appServiceId == null ? null
                : roleMapper.selectByAppAndCode(realmId, appServiceId, roleCode);
    }

    /**
     * 分页查询域内角色。
     *
     * @param realmId   域主键
     * @param condition 查询条件
     * @param pageNum   页码,从 1 开始
     * @param pageSize  每页条数
     * @return 分页结果
     */
    public PageResult<Role> page(Long realmId, Role condition, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Role> list = roleMapper.selectByCondition(realmId, condition);
        PageInfo<Role> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }

    /**
     * 查询应用作用域内的全部角色(APP 角色与已绑定该应用的 CLIENT 角色)。
     *
     * @param realmId      域主键
     * @param appServiceId 应用服务主键,为空时返回空列表
     * @return 角色列表
     */
    public List<Role> listByAppServiceId(Long realmId, Long appServiceId) {
        if (appServiceId == null) {
            return List.of();
        }
        Role condition = new Role();
        condition.setAppServiceId(appServiceId);
        return roleMapper.selectByCondition(realmId, condition);
    }

    /**
     * 按容器类型查询域内角色,供角色映射选择来源。
     *
     * @param realmId       域主键
     * @param containerType 容器类型(REALM / CLIENT / APP)
     * @return 角色列表
     */
    public List<Role> listByContainerType(Long realmId, String containerType) {
        Role condition = new Role();
        condition.setContainerType(containerType);
        return roleMapper.selectByCondition(realmId, condition);
    }

    /**
     * 新建角色,并自增该域权限缓存代次。
     *
     * @param role 角色实体
     * @return 影响行数
     */
    public int create(Role role) {
        int rows = roleMapper.insert(role);
        permissionEpoch.bump(role.getRealmId());
        return rows;
    }

    /**
     * 更新角色非空字段,并自增该域权限缓存代次。
     *
     * @param role 角色实体
     * @return 影响行数
     */
    public int update(Role role) {
        int rows = roleMapper.updateById(role);
        permissionEpoch.bump(role.getRealmId());
        return rows;
    }

    /**
     * 变更角色的容器归属,并自增该域权限缓存代次。
     * <p>归属决定角色的授权面,调用方负责判定"该角色是否允许改归属"。</p>
     *
     * @param role          角色实体,id 必填
     * @param containerType 目标容器类型
     * @param clientId      目标所属客户端,非 CLIENT 容器传 null
     * @param appServiceId  目标所属应用,REALM 容器传 null
     * @return 受影响行数
     */
    public int changeContainer(Role role, String containerType, Long clientId, Long appServiceId) {
        int rows = roleMapper.updateContainerById(role.getId(), containerType, clientId, appServiceId);
        permissionEpoch.bump(role.getRealmId());
        return rows;
    }

    /**
     * 统计角色承载的授权关系条数(分配 + 授予 + 应用授权)。
     *
     * @param roleId 角色主键
     * @return 使用条数;为 0 表示角色尚未承载任何授权关系
     */
    public long countUsage(Long roleId) {
        return roleMapper.countUsage(roleId);
    }

    /**
     * 逻辑删除角色,并自增其所属域的权限缓存代次。
     *
     * @param id 角色主键
     * @return 影响行数
     */
    public int remove(Long id) {
        // 删除前先取域标识:代次按域自增,删除动作本身不携带域
        Role existing = roleMapper.selectById(id);
        int rows = roleMapper.softDeleteById(id);
        if (existing != null) {
            permissionEpoch.bump(existing.getRealmId());
        }
        return rows;
    }

    /**
     * 查询用户已分配的角色,管理端展示用,不做启用状态过滤。
     *
     * @param userId 用户主键
     * @return 角色列表
     */
    public List<Role> listRolesByUserId(Long userId) {
        List<UserRole> userRoles = userRoleMapper.selectByUserId(userId);
        if (userRoles.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectByIds(userRoles.stream().map(UserRole::getRoleId).toList());
    }

    /**
     * 覆盖式分配用户角色:以目标集合为准,新增缺失的关联、移除多余的关联。
     * <p>完成后自增该域权限缓存代次。</p>
     *
     * @param realmId   域主键
     * @param userId    用户主键
     * @param roleIds   目标角色主键集合,空集合表示清空
     * @param grantedBy 操作人主键
     */
    @Transactional
    public void assignRoles(Long realmId, Long userId, List<Long> roleIds, Long grantedBy) {
        // 1. 现有角色集合
        Set<Long> current = new HashSet<>();
        for (UserRole userRole : userRoleMapper.selectByUserId(userId)) {
            current.add(userRole.getRoleId());
        }
        Set<Long> target = new HashSet<>(roleIds == null ? List.of() : roleIds);
        // 2. 新增目标中尚未分配的关联
        for (Long roleId : target) {
            if (!current.contains(roleId)) {
                UserRole userRole = new UserRole();
                userRole.setRealmId(realmId);
                userRole.setUserId(userId);
                userRole.setRoleId(roleId);
                userRole.setGrantedBy(grantedBy);
                userRoleMapper.insert(userRole);
            }
        }
        // 3. 移除已不在目标中的关联
        for (Long roleId : current) {
            if (!target.contains(roleId)) {
                userRoleMapper.deleteByUserIdAndRoleId(userId, roleId);
            }
        }
        // 4. 该域权限缓存立即失效:角色分配变化直接影响权限求值结果
        permissionEpoch.bump(realmId);
    }
}
