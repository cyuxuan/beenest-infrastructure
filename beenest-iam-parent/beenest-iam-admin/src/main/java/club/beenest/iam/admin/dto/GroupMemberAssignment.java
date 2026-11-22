package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 组成员任职指派。
 *
 * @param userId     成员用户主键
 * @param positionId 在该组担任的岗位(为空表示只归属不任职)
 * @param primary    是否为主部门;一次提交里至多一个为 true
 */
public record GroupMemberAssignment(
        @NotNull(message = "成员用户主键不能为空") Long userId,
        Long positionId,
        Boolean primary
) {
}
