package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Size;

/**
 * 用户组更新请求(仅更新显式给出的字段)。
 *
 * @param parentId     父组主键(不传表示不调整)
 * @param groupName    组名称
 * @param clearParent  是否把父组置空(提升为顶级组)
 * @param groupType    组织类型:DEPARTMENT / GROUP
 * @param leaderUserId 部门负责人
 * @param clearLeader  是否清空负责人
 * @param sortOrder    排序号
 */
public record GroupUpdateRequest(
        Long parentId,
        @Size(max = 100, message = "组名称长度不能超过 100")
        String groupName,
        Boolean clearParent,
        String groupType,
        Long leaderUserId,
        Boolean clearLeader,
        Integer sortOrder
) {
}
