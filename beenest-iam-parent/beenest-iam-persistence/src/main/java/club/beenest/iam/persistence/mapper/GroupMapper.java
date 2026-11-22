package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.Group;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户组 Mapper(iam_group 单表)。
 * <p>组以 parent_id 组织为树,查询与更新均排除逻辑删除行。</p>
 */
public interface GroupMapper {

    /**
     * 按主键查询未删除组。
     *
     * @param id 组主键
     * @return 组;不存在或已删除返回 null
     */
    Group selectById(@Param("id") Long id);

    /**
     * 按域与组编码查询未删除组,用于编码唯一性校验。
     *
     * @param realmId   身份域主键
     * @param groupCode 组编码
     * @return 组;不存在返回 null
     */
    Group selectByRealmIdAndCode(@Param("realmId") Long realmId, @Param("groupCode") String groupCode);

    /**
     * 分页查询域内未删除组,按排序号与主键升序。
     * <p>过滤维度:编码/名称模糊匹配,上级组与组类型精确匹配;条件为空时返回域内全部组。</p>
     *
     * @param realmId 身份域主键
     * @param group   过滤条件,可为 null
     * @return 组列表;无匹配时返回空列表
     */
    List<Group> selectByCondition(@Param("realmId") Long realmId, @Param("group") Group group);

    /**
     * 查询域内全部未删除组,按排序号与主键升序。
     *
     * @param realmId 身份域主键
     * @return 组列表;无数据时返回空列表
     */
    List<Group> selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 更新上级组。
     * <p>parentId 为空时写入 NULL,用于清空上级组。</p>
     *
     * @param id       组主键
     * @param parentId 新的上级组;为空表示提升为顶级组
     * @return 受影响行数
     */
    int updateParentId(@Param("id") Long id, @Param("parentId") Long parentId);

    /**
     * 更新部门负责人。
     * <p>leaderUserId 为空时写入 NULL,用于清空负责人。</p>
     *
     * @param id           组主键
     * @param leaderUserId 负责人用户主键;为空表示清空负责人
     * @return 受影响行数
     */
    int updateLeaderUserId(@Param("id") Long id, @Param("leaderUserId") Long leaderUserId);

    /**
     * 按主键批量查询未删除组。
     *
     * @param ids 组主键列表,不能为空
     * @return 组列表;顺序不保证
     */
    List<Group> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 查询指定组的直接子组,按排序号与主键升序。
     *
     * @param parentId 上级组主键
     * @return 子组列表;无子组时返回空列表
     */
    List<Group> selectByParentId(@Param("parentId") Long parentId);

    /**
     * 插入组,deleted 固定写入 FALSE。
     *
     * @param group 组实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(Group group);

    /**
     * 按主键更新非空字段并刷新更新时间。
     * <p>上级组与负责人传 null 时跳过,清空需调用对应方法。</p>
     *
     * @param group 组实体,id 必填
     * @return 受影响行数
     */
    int updateById(Group group);

    /**
     * 把被删组的直接子组改挂到指定父级。
     *
     * @param groupId     被删除的组主键
     * @param newParentId 新的父级主键;为空表示子组提升为顶级组
     * @return 受影响的子组数
     */
    int reparentChildren(@Param("groupId") Long groupId, @Param("newParentId") Long newParentId);

    /**
     * 逻辑删除组。
     *
     * @param id 组主键
     * @return 受影响行数;组不存在或已删除时为 0
     */
    int softDeleteById(@Param("id") Long id);

    /**
     * 统计域内未删除组数。
     *
     * @param realmId 身份域主键
     * @return 组数
     */
    long countByRealm(@Param("realmId") Long realmId);
}
