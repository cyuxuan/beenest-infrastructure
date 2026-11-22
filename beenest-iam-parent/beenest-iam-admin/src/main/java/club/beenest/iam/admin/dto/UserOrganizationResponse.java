package club.beenest.iam.admin.dto;

/**
 * 用户的组织归属(任职)视图:用户视角的部门与岗位。
 *
 * @param groupId      部门主键
 * @param groupCode    部门编码
 * @param groupName    部门名称
 * @param groupType    组织类型(DEPARTMENT / GROUP)
 * @param positionId   岗位主键
 * @param positionCode 岗位编码
 * @param positionName 岗位名称
 * @param primary      是否主部门
 */
public record UserOrganizationResponse(
        Long groupId,
        String groupCode,
        String groupName,
        String groupType,
        Long positionId,
        String positionCode,
        String positionName,
        boolean primary
) {
}
