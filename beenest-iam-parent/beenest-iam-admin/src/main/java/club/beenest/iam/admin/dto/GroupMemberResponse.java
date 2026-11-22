package club.beenest.iam.admin.dto;

/**
 * 组成员(任职)视图。
 * <p>一条记录对应一名成员在一个组内的任职信息。</p>
 *
 * @param userId       成员用户主键
 * @param username     登录名
 * @param nickname     昵称
 * @param userStatus   账号状态
 * @param groupId      成员所属(子)部门主键;含下级查询时可能不是当前组
 * @param groupName    成员所属部门名称
 * @param positionId   岗位主键
 * @param positionCode 岗位编码
 * @param positionName 岗位名称
 * @param primary      是否主部门
 */
public record GroupMemberResponse(
        Long userId,
        String username,
        String nickname,
        Integer userStatus,
        Long groupId,
        String groupName,
        Long positionId,
        String positionCode,
        String positionName,
        boolean primary
) {
}
