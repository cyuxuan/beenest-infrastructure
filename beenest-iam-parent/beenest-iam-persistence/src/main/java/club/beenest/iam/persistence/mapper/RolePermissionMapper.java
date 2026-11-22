package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.RolePermission;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色-权限直接关联 Mapper(iam_role_permission 单表)。
 * <p>该关联表示不经菜单的直接授权,与菜单派生权限合并参与权限求值;删除为逻辑删除。</p>
 */
public interface RolePermissionMapper {

    /**
     * 查询角色的全部未删除直接权限关联。
     *
     * @param roleId 角色主键
     * @return 关联列表;无直接授权时返回空列表
     */
    List<RolePermission> selectByRoleId(@Param("roleId") Long roleId);

    /**
     * 查询角色集合的未删除直接权限关联,用于权限求值。
     *
     * @param roleIds 角色主键集合
     * @return 关联列表;集合为空时返回空列表
     */
    List<RolePermission> selectByRoleIds(@Param("roleIds") List<Long> roleIds);

    /**
     * 查询权限集合被哪些角色直接引用,用于回填报告中的共享判定。
     *
     * @param permissionIds 权限主键集合
     * @return 关联列表;集合为空时返回空列表
     */
    List<RolePermission> selectByPermissionIds(@Param("permissionIds") List<Long> permissionIds);

    /**
     * 统计直接引用该权限的未删除角色数,用于删除权限前检查。
     *
     * @param permissionId 权限主键
     * @return 角色数;已删除角色不计入
     */
    int countByPermissionId(@Param("permissionId") Long permissionId);

    /**
     * 把一条角色-权限关联改指向另一个权限,回填复制时使用。
     *
     * @param id           关联主键
     * @param permissionId 新的权限主键
     * @return 受影响行数
     */
    int updatePermissionIdById(@Param("id") Long id, @Param("permissionId") Long permissionId);

    /**
     * 逻辑删除一条角色-权限关联。
     *
     * @param id 关联主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);

    /**
     * 逻辑删除角色的全部直接权限关联,整体覆盖授权前调用。
     *
     * @param roleId 角色主键
     */
    void softDeleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 批量写入角色-权限关联。
     *
     * @param realmId       身份域主键
     * @param roleId        角色主键
     * @param permissionIds 权限主键列表,不能为空
     */
    void insertBatch(@Param("realmId") Long realmId, @Param("roleId") Long roleId,
                     @Param("permissionIds") List<Long> permissionIds);
}
