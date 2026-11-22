package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.Position;
import club.beenest.iam.persistence.entity.PositionRole;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.mapper.PositionMapper;
import club.beenest.iam.persistence.mapper.PositionRoleMapper;
import club.beenest.iam.persistence.mapper.RoleMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 岗位存储实现(iam_position / iam_position_role)。
 * <p>岗位是人事语义,本身不参与鉴权;岗位挂载的角色经由"用户在部门任职该岗位"
 * 进入权限计算(见 {@link IamEffectiveRoleResolver})。因此任何岗位或岗位角色的
 * 变更都要抬升权限代次,让缓存立即失效。</p>
 */
@Component
public class IamPositionManager {

    private final PositionMapper positionMapper;
    private final PositionRoleMapper positionRoleMapper;
    private final RoleMapper roleMapper;
    /** 权限缓存代次:岗位相关变更后自增 */
    private final IamPermissionEpoch permissionEpoch;

    public IamPositionManager(PositionMapper positionMapper, PositionRoleMapper positionRoleMapper,
                              RoleMapper roleMapper, IamPermissionEpoch permissionEpoch) {
        this.positionMapper = positionMapper;
        this.positionRoleMapper = positionRoleMapper;
        this.roleMapper = roleMapper;
        this.permissionEpoch = permissionEpoch;
    }

    // ===== 岗位 CRUD =====

    /**
     * 按主键查询岗位。
     *
     * @param id 岗位主键
     * @return 岗位,不存在时返回 null
     */
    public Position getById(Long id) {
        return positionMapper.selectById(id);
    }

    /**
     * 按域与岗位编码查询,用于唯一性校验。
     *
     * @param realmId      域主键
     * @param positionCode 岗位编码
     * @return 岗位,不存在时返回 null
     */
    public Position getByCode(Long realmId, String positionCode) {
        return positionMapper.selectByRealmIdAndCode(realmId, positionCode);
    }

    /**
     * 查询域内全部岗位。
     *
     * @param realmId 域主键
     * @return 岗位列表
     */
    public List<Position> listByRealm(Long realmId) {
        return positionMapper.selectByRealmId(realmId);
    }

    /**
     * 分页查询域内岗位。
     *
     * @param realmId   域主键
     * @param condition 查询条件
     * @param pageNum   页码,从 1 开始
     * @param pageSize  每页条数
     * @return 分页结果
     */
    public PageResult<Position> page(Long realmId, Position condition, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Position> list = positionMapper.selectByCondition(realmId, condition);
        PageInfo<Position> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }

    /**
     * 新建岗位,并自增该域权限缓存代次。
     *
     * @param position 岗位实体
     * @return 影响行数
     */
    public int create(Position position) {
        int rows = positionMapper.insert(position);
        permissionEpoch.bump(position.getRealmId());
        return rows;
    }

    /**
     * 更新岗位非空字段,并自增该域权限缓存代次。
     *
     * @param position 岗位实体
     * @return 影响行数
     */
    public int update(Position position) {
        int rows = positionMapper.updateById(position);
        permissionEpoch.bump(position.getRealmId());
        return rows;
    }

    /**
     * 逻辑删除岗位,级联清理岗位角色关联,并自增所属域的权限缓存代次。
     * <p>任职记录保留:删除岗位只撤销该岗位携带的角色,用户仍留在原部门,
     * 历史组织关系可追溯。</p>
     *
     * @param id 岗位主键
     * @return 影响行数
     */
    @Transactional
    public int remove(Long id) {
        Position existing = positionMapper.selectById(id);
        positionRoleMapper.deleteByPositionId(id);
        int rows = positionMapper.softDeleteById(id);
        if (existing != null) {
            permissionEpoch.bump(existing.getRealmId());
        }
        return rows;
    }

    /**
     * 统计域内岗位数。
     *
     * @param realmId 域主键
     * @return 岗位数量
     */
    public long countByRealm(Long realmId) {
        return positionMapper.countByRealm(realmId);
    }

    // ===== 岗位角色 =====

    /**
     * 查询岗位已挂载的角色。
     *
     * @param positionId 岗位主键
     * @return 角色列表
     */
    public List<Role> listRoles(Long positionId) {
        List<Long> roleIds = positionRoleMapper.selectByPositionId(positionId).stream()
                .map(PositionRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectByIds(roleIds);
    }

    /**
     * 批量统计各岗位已挂载的角色数,单次查询取回整页关联后在内存计数。
     *
     * @param positionIds 岗位主键集合
     * @return 岗位主键到角色数的映射;无关联的岗位不出现在结果中
     */
    public Map<Long, Long> countRoles(List<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (PositionRole relation : positionRoleMapper.selectByPositionIds(positionIds)) {
            counts.merge(relation.getPositionId(), 1L, Long::sum);
        }
        return counts;
    }

    /**
     * 覆盖式分配岗位角色:以目标集合为准,新增缺失关联、移除多余关联。
     * <p>岗位角色经任职落到用户身上,影响范围为全域所有任该岗位的用户;
     * 完成后自增该域权限缓存代次。</p>
     *
     * @param realmId   域主键
     * @param positionId 岗位主键
     * @param roleIds   目标角色主键集合,为空表示清空岗位角色
     * @param grantedBy 授权人用户主键
     */
    @Transactional
    public void assignRoles(Long realmId, Long positionId, List<Long> roleIds, Long grantedBy) {
        Set<Long> current = new HashSet<>();
        for (PositionRole positionRole : positionRoleMapper.selectByPositionId(positionId)) {
            current.add(positionRole.getRoleId());
        }
        Set<Long> target = new HashSet<>(roleIds == null ? List.of() : roleIds);
        for (Long roleId : target) {
            if (!current.contains(roleId)) {
                PositionRole positionRole = new PositionRole();
                positionRole.setRealmId(realmId);
                positionRole.setPositionId(positionId);
                positionRole.setRoleId(roleId);
                positionRole.setGrantedBy(grantedBy);
                positionRoleMapper.insert(positionRole);
            }
        }
        for (Long roleId : current) {
            if (!target.contains(roleId)) {
                positionRoleMapper.deleteByPositionIdAndRoleId(positionId, roleId);
            }
        }
        permissionEpoch.bump(realmId);
    }

    /**
     * 解析岗位集合携带的角色主键,供权限计算使用。
     * <p>单次批量查询取回全部关联。</p>
     *
     * @param positionIds 岗位主键集合
     * @return 去重后的角色主键;入参为空时返回空列表
     */
    public List<Long> resolveRoleIds(List<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return List.of();
        }
        Set<Long> roleIds = new HashSet<>();
        for (PositionRole positionRole : positionRoleMapper.selectByPositionIds(positionIds)) {
            roleIds.add(positionRole.getRoleId());
        }
        return List.copyOf(roleIds);
    }

    /**
     * 按主键批量查询岗位,供任职回显。
     *
     * @param ids 岗位主键集合
     * @return 岗位列表;入参为空时返回空列表
     */
    public List<Position> listByIds(List<Long> ids) {
        return (ids == null || ids.isEmpty()) ? List.of() : positionMapper.selectByIds(ids);
    }
}
