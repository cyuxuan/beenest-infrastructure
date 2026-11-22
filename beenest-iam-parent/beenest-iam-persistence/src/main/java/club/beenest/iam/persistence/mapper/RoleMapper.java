package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.Role;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色 Mapper(iam_role 单表)。
 * <p>角色按容器类型分为 REALM、APP、CLIENT 三种作用域;查询排除逻辑删除行。</p>
 */
public interface RoleMapper {

    /**
     * 按主键查询未删除角色。
     *
     * @param id 角色主键
     * @return 角色;不存在或已删除返回 null
     */
    Role selectById(@Param("id") Long id);

    /**
     * 按主键集合批量查询未删除角色。
     *
     * @param ids 角色主键集合
     * @return 角色列表;集合为空时返回空列表
     */
    List<Role> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 分页查询域内未删除角色,按主键升序。
     * <p>过滤维度:角色编码与名称模糊匹配,容器类型、所属应用与客户端精确匹配;
     * 未指定容器类型时按是否给定应用切换域级或应用级的可见范围。</p>
     *
     * @param realmId   身份域主键
     * @param condition 过滤条件,可为 null
     * @return 角色列表;无匹配时返回空列表
     */
    List<Role> selectByCondition(@Param("realmId") Long realmId, @Param("role") Role condition);

    /**
     * 查询域内全部未删除角色,含所有容器类型,按主键升序。
     *
     * @param realmId 身份域主键
     * @return 角色列表;无数据时返回空列表
     */
    List<Role> selectAllByRealmId(@Param("realmId") Long realmId);

    /**
     * 插入角色,deleted 固定写入 FALSE。
     *
     * @param role 角色实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(Role role);

    /**
     * 按主键更新非空字段并刷新更新时间。
     * <p>角色编码、容器类型与归属不可修改。</p>
     *
     * @param role 角色实体,id 必填
     * @return 受影响行数
     */
    int updateById(Role role);

    /**
     * 变更角色的容器归属(容器类型、所属客户端、所属应用)。
     * <p>归属决定角色的授权面,不参与通用更新;该语句显式写入三列,
     * 传 null 即清空(如域级角色不挂应用与客户端)。</p>
     *
     * @param id            角色主键
     * @param containerType 容器类型 REALM / APP / CLIENT
     * @param clientId      所属客户端,非 CLIENT 容器传 null
     * @param appServiceId  所属应用,REALM 容器传 null
     * @return 受影响行数
     */
    int updateContainerById(@Param("id") Long id,
                            @Param("containerType") String containerType,
                            @Param("clientId") Long clientId,
                            @Param("appServiceId") Long appServiceId);

    /**
     * 统计角色被使用的条数:用户/组/岗位分配、直接权限授予、菜单授予与应用授权。
     * <p>归属变更前用它判断"这个角色是否已经承载了授权关系"。</p>
     *
     * @param roleId 角色主键
     * @return 使用条数;为 0 表示可以安全变更归属
     */
    long countUsage(@Param("roleId") Long roleId);

    /**
     * 逻辑删除角色。
     *
     * @param id 角色主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);

    /**
     * 按应用与角色编码查询未删除角色,含历史 CLIENT 容器角色。
     *
     * @param realmId      身份域主键
     * @param appServiceId 应用主键
     * @param roleCode     角色编码
     * @return 角色;不存在返回 null
     */
    Role selectByAppAndCode(@Param("realmId") Long realmId,
                            @Param("appServiceId") Long appServiceId,
                            @Param("roleCode") String roleCode);

    /**
     * 按作用域查询未删除角色,用于唯一性校验。
     * <p>REALM 容器 clientId 与 appServiceId 传 null,APP 容器传应用主键,CLIENT 历史容器传客户端主键。</p>
     *
     * @param realmId       身份域主键
     * @param containerType 容器类型
     * @param clientId      客户端主键
     * @param appServiceId  应用主键
     * @param roleCode      角色编码
     * @return 角色;不存在返回 null
     */
    Role selectByScope(@Param("realmId") Long realmId,
                       @Param("containerType") String containerType,
                       @Param("clientId") Long clientId,
                       @Param("appServiceId") Long appServiceId,
                       @Param("roleCode") String roleCode);
}
