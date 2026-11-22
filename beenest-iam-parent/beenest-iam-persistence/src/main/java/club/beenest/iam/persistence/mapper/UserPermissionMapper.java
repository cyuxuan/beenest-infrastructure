package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.UserPermission;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户级特殊授权/撤销 Mapper(iam_user_permission 单表)。
 * <p>同一用户与权限仅一条有效记录,删除为逻辑删除。</p>
 */
public interface UserPermissionMapper {

    /**
     * 按主键查询未删除特批记录。
     *
     * @param id 特批记录主键
     * @return 特批记录;不存在或已删除返回 null
     */
    UserPermission selectById(@Param("id") Long id);

    /**
     * 查询用户的全部未删除特批记录,含已过期记录,按创建时间与主键倒序。
     *
     * @param userId 用户主键
     * @return 特批记录列表;无记录时返回空列表
     */
    List<UserPermission> selectByUserId(@Param("userId") Long userId);

    /**
     * 查询用户对单个权限的未删除特批记录。
     *
     * @param userId       用户主键
     * @param permissionId 权限主键
     * @return 特批记录;不存在返回 null
     */
    UserPermission selectByUserAndPermission(@Param("userId") Long userId,
                                             @Param("permissionId") Long permissionId);

    /**
     * 统计引用该权限的未删除特批记录数,用于删除权限前检查。
     *
     * @param permissionId 权限主键
     * @return 记录数
     */
    int countByPermissionId(@Param("permissionId") Long permissionId);

    /**
     * 写入特批关系。
     * <p>同用户同权限已有有效记录时覆盖授权类型、原因、过期时间与操作人;
     * 唯一性由部分唯一索引保证。</p>
     *
     * @param userPermission 特批记录实体
     * @return 受影响行数
     */
    int upsert(UserPermission userPermission);

    /**
     * 逻辑删除特批记录。
     *
     * @param id 特批记录主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);
}
