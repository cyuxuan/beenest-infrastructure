package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.PositionRole;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 岗位-角色关联 Mapper(iam_position_role 单表)。
 * <p>岗位上的角色参与该岗位任职者的权限计算;删除为逻辑删除。</p>
 */
public interface PositionRoleMapper {

    /**
     * 查询岗位已关联的未删除角色。
     *
     * @param positionId 岗位主键
     * @return 关联列表;未关联角色时返回空列表
     */
    List<PositionRole> selectByPositionId(@Param("positionId") Long positionId);

    /**
     * 批量查询多个岗位的未删除角色关联,用于权限计算。
     *
     * @param positionIds 岗位主键列表,不能为空
     * @return 关联列表;顺序不保证
     */
    List<PositionRole> selectByPositionIds(@Param("positionIds") List<Long> positionIds);

    /**
     * 查询持有该角色的全部未删除岗位关联,用于删除角色前的引用检查。
     *
     * @param roleId 角色主键
     * @return 关联列表;无引用时返回空列表
     */
    List<PositionRole> selectByRoleId(@Param("roleId") Long roleId);

    /**
     * 插入关联,deleted 固定写入 FALSE。
     *
     * @param positionRole 关联实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(PositionRole positionRole);

    /**
     * 逻辑删除岗位的某条角色关联。
     *
     * @param positionId 岗位主键
     * @param roleId     角色主键
     * @return 受影响行数
     */
    int deleteByPositionIdAndRoleId(@Param("positionId") Long positionId, @Param("roleId") Long roleId);

    /**
     * 逻辑删除岗位的全部角色关联,删岗位时调用。
     *
     * @param positionId 岗位主键
     * @return 受影响行数
     */
    int deleteByPositionId(@Param("positionId") Long positionId);
}
