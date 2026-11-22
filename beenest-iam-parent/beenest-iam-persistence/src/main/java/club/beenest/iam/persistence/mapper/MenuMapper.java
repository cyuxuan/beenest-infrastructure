package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.Menu;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 菜单 Mapper(iam_menu 单表)。
 * <p>菜单按应用归属,app_service_id 为空表示平台级菜单;树在应用层拼装,查询均排除逻辑删除行。</p>
 */
public interface MenuMapper {

    /**
     * 按主键查询未删除菜单。
     *
     * @param id 菜单主键
     * @return 菜单;不存在或已删除返回 null
     */
    Menu selectById(@Param("id") Long id);

    /**
     * 按主键集合批量查询未删除菜单。
     *
     * @param ids 菜单主键集合
     * @return 菜单列表;集合为空时返回空列表
     */
    List<Menu> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 查询域内全部未删除菜单,按排序号与主键升序。
     *
     * @param realmId 身份域主键
     * @return 菜单列表;无数据时返回空列表
     */
    List<Menu> selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 查询域内指定应用的未删除菜单,按排序号与主键升序。
     *
     * @param realmId      身份域主键
     * @param appServiceId 应用主键
     * @return 菜单列表;无数据时返回空列表
     */
    List<Menu> selectByAppServiceId(@Param("realmId") Long realmId, @Param("appServiceId") Long appServiceId);

    /**
     * 查询域内未归属应用的平台级菜单,按排序号与主键升序。
     *
     * @param realmId 身份域主键
     * @return 菜单列表;无数据时返回空列表
     */
    List<Menu> selectPlatformMenus(@Param("realmId") Long realmId);

    /**
     * 把平台级菜单批量归属到应用。
     * <p>只更新仍处于平台命名空间的未删除菜单,重复执行不产生额外变更。</p>
     *
     * @param realmId      身份域主键
     * @param appServiceId 目标应用主键
     * @param ids          菜单主键列表,不能为空
     * @return 受影响行数
     */
    int updateAppServiceIdByIds(@Param("realmId") Long realmId,
                                @Param("appServiceId") Long appServiceId,
                                @Param("ids") List<Long> ids);

    /**
     * 统计引用该权限的未删除菜单数,用于删除权限前检查。
     *
     * @param permissionId 权限主键
     * @return 菜单数
     */
    int countByPermissionId(@Param("permissionId") Long permissionId);

    /**
     * 把菜单改挂到另一个权限。
     *
     * @param id           菜单主键
     * @param permissionId 新的权限主键
     * @return 受影响行数
     */
    int updatePermissionIdById(@Param("id") Long id, @Param("permissionId") Long permissionId);

    /**
     * 新建菜单,deleted 固定写入 FALSE。
     *
     * @param menu 菜单实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(Menu menu);

    /**
     * 按主键更新非空字段并刷新更新时间。
     * <p>菜单编码与所属应用不在此处更新。</p>
     *
     * @param menu 菜单实体,id 必填
     * @return 受影响行数
     */
    int updateById(Menu menu);

    /**
     * 逻辑删除菜单。
     *
     * @param id 菜单主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);

    /**
     * 按主键集合批量逻辑删除菜单。
     *
     * @param ids 菜单主键集合
     * @return 受影响行数;集合为空时不产生变更
     */
    int softDeleteByIds(@Param("ids") List<Long> ids);
}
