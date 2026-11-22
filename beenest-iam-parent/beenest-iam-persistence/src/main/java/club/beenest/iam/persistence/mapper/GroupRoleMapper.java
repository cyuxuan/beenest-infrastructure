package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.GroupRole;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 组-角色关联 Mapper(iam_group_role 单表)。
 * <p>组上授予的角色参与组内成员的权限计算;删除为逻辑删除。</p>
 */
public interface GroupRoleMapper {

    /**
     * 查询组的全部未删除角色关联。
     *
     * @param groupId 组主键
     * @return 关联列表;未授予角色时返回空列表
     */
    List<GroupRole> selectByGroupId(@Param("groupId") Long groupId);

    /**
     * 查询角色的全部未删除组关联。
     *
     * @param roleId 角色主键
     * @return 关联列表;无所属组时返回空列表
     */
    List<GroupRole> selectByRoleId(@Param("roleId") Long roleId);

    /**
     * 批量查询多个组的未删除角色关联。
     *
     * @param groupIds 组主键列表,不能为空
     * @return 关联列表;顺序不保证
     */
    List<GroupRole> selectByGroupIds(@Param("groupIds") List<Long> groupIds);

    /**
     * 插入关联,deleted 固定写入 FALSE。
     *
     * @param groupRole 关联实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(GroupRole groupRole);

    /**
     * 逻辑删除组的某条角色关联。
     *
     * @param groupId 组主键
     * @param roleId  角色主键
     * @return 受影响行数
     */
    int deleteByGroupIdAndRoleId(@Param("groupId") Long groupId, @Param("roleId") Long roleId);

    /**
     * 逻辑删除组的全部角色关联,删组时调用。
     *
     * @param groupId 组主键
     * @return 受影响行数
     */
    int deleteByGroupId(@Param("groupId") Long groupId);
}
