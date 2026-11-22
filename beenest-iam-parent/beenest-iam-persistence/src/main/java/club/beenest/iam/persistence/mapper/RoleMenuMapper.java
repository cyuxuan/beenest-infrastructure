package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.RoleMenu;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色-菜单关联 Mapper(iam_role_menu 单表)。
 * <p>授予菜单即派生菜单上挂载的权限;删除为逻辑删除。</p>
 */
public interface RoleMenuMapper {

    /**
     * 查询角色的全部未删除菜单关联。
     *
     * @param roleId 角色主键
     * @return 关联列表;未授予菜单时返回空列表
     */
    List<RoleMenu> selectByRoleId(@Param("roleId") Long roleId);

    /**
     * 查询角色集合的未删除菜单关联,用于权限求值。
     *
     * @param roleIds 角色主键集合
     * @return 关联列表;集合为空时返回空列表
     */
    List<RoleMenu> selectByRoleIds(@Param("roleIds") List<Long> roleIds);

    /**
     * 查询菜单集合被哪些角色引用,用于回填报告中的共享判定。
     *
     * @param menuIds 菜单主键集合
     * @return 关联列表;集合为空时返回空列表
     */
    List<RoleMenu> selectByMenuIds(@Param("menuIds") List<Long> menuIds);

    /**
     * 把一条角色-菜单关联改指向另一个菜单,回填复制时使用。
     *
     * @param id     关联主键
     * @param menuId 新的菜单主键
     * @return 受影响行数
     */
    int updateMenuIdById(@Param("id") Long id, @Param("menuId") Long menuId);

    /**
     * 逻辑删除一条角色-菜单关联。
     *
     * @param id 关联主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);

    /**
     * 批量写入角色-菜单关联。
     *
     * @param realmId 身份域主键
     * @param roleId  角色主键
     * @param menuIds 菜单主键列表,不能为空
     * @return 受影响行数
     */
    int insertBatch(@Param("realmId") Long realmId, @Param("roleId") Long roleId, @Param("menuIds") List<Long> menuIds);

    /**
     * 逻辑删除角色的全部菜单关联,整体覆盖写入前调用。
     *
     * @param roleId 角色主键
     * @return 受影响行数
     */
    int softDeleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 按菜单集合逻辑删除关联,删除菜单时调用。
     *
     * @param menuIds 菜单主键集合
     * @return 受影响行数;集合为空时不产生变更
     */
    int softDeleteByMenuIds(@Param("menuIds") List<Long> menuIds);
}
