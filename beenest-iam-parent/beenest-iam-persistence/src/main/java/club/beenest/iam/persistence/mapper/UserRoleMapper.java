package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.UserRole;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户-角色关联 Mapper(iam_user_role 单表)。
 * <p>用户直接授予的角色参与权限计算;删除为逻辑删除。</p>
 */
public interface UserRoleMapper {

    /**
     * 查询用户的全部未删除角色关联,不含角色详情。
     *
     * @param userId 用户主键
     * @return 关联列表;未授予角色时返回空列表
     */
    List<UserRole> selectByUserId(@Param("userId") Long userId);

    /**
     * 查询角色下的全部未删除用户关联,用于缓存失效扇出。
     *
     * @param roleId 角色主键
     * @return 关联列表;无用户时返回空列表
     */
    List<UserRole> selectByRoleId(@Param("roleId") Long roleId);

    /**
     * 插入关联,deleted 固定写入 FALSE。
     *
     * @param userRole 关联实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(UserRole userRole);

    /**
     * 逻辑删除用户的某条角色关联。
     *
     * @param userId 用户主键
     * @param roleId 角色主键
     * @return 受影响行数
     */
    int deleteByUserIdAndRoleId(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
