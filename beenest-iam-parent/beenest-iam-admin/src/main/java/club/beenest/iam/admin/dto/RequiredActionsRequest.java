package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 设置用户待办事项请求(覆盖式)。
 *
 * @param actions 动作代码列表;空列表表示清空待办
 */
public record RequiredActionsRequest(

        @NotNull(message = "待办列表不能为空(清空请传空数组)")
        List<String> actions
) {
}
