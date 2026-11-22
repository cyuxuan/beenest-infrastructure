package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量启用/禁用用户请求。
 *
 * @param ids    用户主键集合(必须全部属于当前域,由服务层校验)
 * @param status 目标状态:1 启用 / 0 禁用
 */
public record UserBatchStatusRequest(
        @NotEmpty(message = "用户主键集合不能为空") List<@NotNull Long> ids,
        @NotNull(message = "目标状态不能为空") Integer status
) {
}
