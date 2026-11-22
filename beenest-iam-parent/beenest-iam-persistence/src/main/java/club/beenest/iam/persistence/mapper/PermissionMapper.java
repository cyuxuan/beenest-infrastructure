package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.Permission;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 权限 Mapper(iam_permission 单表)。
 * <p>权限按域隔离,app_service_id 为空表示平台权限;权限码、资源、动作创建后不可修改,查询均排除逻辑删除行。</p>
 */
public interface PermissionMapper {

    /**
     * 按主键查询未删除权限。
     *
     * @param id 权限主键
     * @return 权限;不存在或已删除返回 null
     */
    Permission selectById(@Param("id") Long id);

    /**
     * 按主键集合批量查询未删除权限。
     *
     * @param ids 权限主键集合
     * @return 权限列表;集合为空时返回空列表
     */
    List<Permission> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 查询域内全部未删除权限,按资源与动作升序。
     *
     * @param realmId 身份域主键
     * @return 权限列表;无数据时返回空列表
     */
    List<Permission> selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 按权限命名空间查询未删除权限。
     *
     * @param realmId        身份域主键
     * @param appServiceId   应用主键;为空表示平台权限
     * @param permissionCode 权限码
     * @return 权限;不存在返回 null
     */
    Permission selectByScope(@Param("realmId") Long realmId,
                             @Param("appServiceId") Long appServiceId,
                             @Param("permissionCode") String permissionCode);

    /**
     * 分页查询域内未删除权限,按应用(平台在前)、资源、动作升序。
     * <p>过滤维度:权限编码与名称模糊匹配,资源与所属应用精确匹配;条件为空时返回域内全部权限。</p>
     *
     * @param realmId   身份域主键
     * @param condition 过滤条件,可为 null
     * @return 权限列表;无匹配时返回空列表
     */
    List<Permission> selectByCondition(@Param("realmId") Long realmId,
                                       @Param("permission") Permission condition);

    /**
     * 用户特批选择器:按应用命名空间与关键词查询未删除权限。
     * <p>关键词匹配权限编码或名称,忽略大小写;结果按应用(平台在前)、权限码升序。</p>
     *
     * @param realmId      身份域主键
     * @param appServiceId 应用主键;为空表示不限应用
     * @param keyword      关键词,可为空
     * @return 权限列表;无匹配时返回空列表
     */
    List<Permission> selectForOverride(@Param("realmId") Long realmId,
                                       @Param("appServiceId") Long appServiceId,
                                       @Param("keyword") String keyword);

    /**
     * 查询域内未归属应用的平台级权限,按权限码升序。
     *
     * @param realmId 身份域主键
     * @return 权限列表;无数据时返回空列表
     */
    List<Permission> selectPlatformPermissions(@Param("realmId") Long realmId);

    /**
     * 把平台级权限批量归属到应用。
     * <p>只更新仍处于平台命名空间的未删除权限,重复执行不产生额外变更。</p>
     *
     * @param realmId      身份域主键
     * @param appServiceId 目标应用主键
     * @param ids          权限主键列表,不能为空
     * @return 受影响行数
     */
    int updateAppServiceIdByIds(@Param("realmId") Long realmId,
                                @Param("appServiceId") Long appServiceId,
                                @Param("ids") List<Long> ids);

    /**
     * 插入权限,deleted 固定写入 FALSE。
     *
     * @param permission 权限实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(Permission permission);

    /**
     * 按主键更新权限名称与说明。
     * <p>权限码、资源、动作不可修改。</p>
     *
     * @param permission 权限实体,id 必填
     * @return 受影响行数
     */
    int updateById(Permission permission);

    /**
     * 逻辑删除权限。
     *
     * @param id 权限主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);
}
