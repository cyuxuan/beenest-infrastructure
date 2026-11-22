package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.GroupMemberCount;
import club.beenest.iam.persistence.entity.UserGroup;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户-组关联 Mapper(iam_user_group 单表)。
 * <p>一条关联表示用户在某个部门的任职,is_primary 标记主部门且按用户唯一;查询均排除逻辑删除行。</p>
 */
public interface UserGroupMapper {

    /**
     * 查询用户的全部未删除组关联。
     *
     * @param userId 用户主键
     * @return 关联列表;无所属组时返回空列表
     */
    List<UserGroup> selectByUserId(@Param("userId") Long userId);

    /**
     * 查询某个组的全部未删除成员关联。
     *
     * @param groupId 组主键
     * @return 关联列表;无成员时返回空列表
     */
    List<UserGroup> selectByGroupId(@Param("groupId") Long groupId);

    /**
     * 批量查询多个用户的未删除组关联,用于权限计算。
     *
     * @param userIds 用户主键列表,不能为空
     * @return 关联列表;顺序不保证
     */
    List<UserGroup> selectByUserIds(@Param("userIds") List<Long> userIds);

    /**
     * 插入组关联,deleted 固定写入 FALSE。
     *
     * @param userGroup 组关联实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(UserGroup userGroup);

    /**
     * 逻辑删除用户的某条组关联。
     *
     * @param userId  用户主键
     * @param groupId 组主键
     * @return 受影响行数
     */
    int deleteByUserIdAndGroupId(@Param("userId") Long userId, @Param("groupId") Long groupId);

    /**
     * 逻辑删除组下的全部成员关联。
     *
     * @param groupId 组主键
     * @return 受影响行数
     */
    int deleteByGroupId(@Param("groupId") Long groupId);

    /**
     * 更新一条任职的岗位与主部门标记。
     *
     * @param userId     用户主键
     * @param groupId    组主键
     * @param positionId 岗位主键;为空表示不任职岗位
     * @param isPrimary  是否主部门;为空按非主部门写入
     * @return 受影响行数
     */
    int updateAssignment(@Param("userId") Long userId, @Param("groupId") Long groupId,
                         @Param("positionId") Long positionId, @Param("isPrimary") Boolean isPrimary);

    /**
     * 取消该用户的全部主部门标记。
     * <p>主部门由部分唯一索引 {@code uk_iam_user_group_primary} 保证唯一,索引即时校验,
     * 设置新的主部门前须先清空旧标记,索引即时校验,冲突会直接拒绝更新。</p>
     *
     * @param userId 用户主键
     * @return 受影响行数
     */
    int clearPrimaryForUser(@Param("userId") Long userId);

    /**
     * 查询部门及其全部下级部门的未删除成员关联,按组主键与关联主键升序。
     * <p>递归展开层级上限 64 层,防止历史数据成环导致查询不终止。</p>
     *
     * @param groupId 部门主键
     * @return 关联列表;部门无成员时返回空列表
     */
    List<UserGroup> selectByGroupIdIncludingSubtree(@Param("groupId") Long groupId);

    /**
     * 批量统计各组的成员数。
     * <p>只统计未删除关联;无成员的组不出现在结果中。</p>
     *
     * @param groupIds 组主键列表,不能为空
     * @return 每行含组主键与成员数
     */
    List<GroupMemberCount> countByGroupIds(@Param("groupIds") List<Long> groupIds);
}
