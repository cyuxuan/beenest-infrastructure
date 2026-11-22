package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 用户组响应。
 *
 * @param id           组主键
 * @param parentId     父组主键
 * @param groupCode    组编码
 * @param groupName    组名称
 * @param groupType    组织类型:DEPARTMENT(部门) / GROUP(普通组)
 * @param leaderUserId 部门负责人用户主键
 * @param leaderName   部门负责人名称(展示用)
 * @param sortOrder    排序号
 * @param externalId   外部系统组织主键(HR/AD 同步锚点)
 * @param memberCount  成员数量
 * @param createdTime  创建时间
 */
public record GroupResponse(
        Long id,
        Long parentId,
        String groupCode,
        String groupName,
        String groupType,
        Long leaderUserId,
        String leaderName,
        Integer sortOrder,
        String externalId,
        long memberCount,
        LocalDateTime createdTime
) {
}
