package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户组创建请求。
 *
 * @param parentId     父组主键(为空表示根组)
 * @param groupCode    组编码(域内唯一)
 * @param groupName    组名称
 * @param groupType    组织类型:DEPARTMENT(部门) / GROUP(普通组),为空按 GROUP
 * @param leaderUserId 部门负责人(用户主键)
 * @param sortOrder    排序号
 */
public record GroupCreateRequest(
        Long parentId,
        @NotBlank(message = "组编码不能为空")
        @Size(max = 100, message = "组编码长度不能超过 100")
        @Pattern(regexp = "[A-Za-z0-9_.:-]+", message = "组编码只能包含字母、数字、下划线、点、冒号与短横线")
        String groupCode,
        @NotBlank(message = "组名称不能为空")
        @Size(max = 100, message = "组名称长度不能超过 100")
        String groupName,
        String groupType,
        Long leaderUserId,
        Integer sortOrder
) {
}
