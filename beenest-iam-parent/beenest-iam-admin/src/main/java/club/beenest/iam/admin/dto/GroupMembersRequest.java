package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 组成员设置请求(覆盖式)。
 * <p>每个成员可同时指定组内岗位与是否主部门;岗位决定角色继承,
 * 主部门决定令牌中的 {@code dept} 声明。</p>
 *
 * @param members 目标任职集合(空集合表示清空成员)
 */
public record GroupMembersRequest(
        @NotNull(message = "成员列表不能为空") List<GroupMemberAssignment> members
) {
}
