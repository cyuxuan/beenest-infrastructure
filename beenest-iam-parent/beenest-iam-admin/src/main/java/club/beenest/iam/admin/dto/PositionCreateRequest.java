package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 岗位创建请求。
 *
 * @param positionCode  岗位编码(域内唯一)
 * @param positionName  岗位名称
 * @param positionLevel 职级/序列(展示用,不参与鉴权)
 * @param sortOrder     排序号
 */
public record PositionCreateRequest(
        @NotBlank(message = "岗位编码不能为空")
        @Size(max = 100, message = "岗位编码长度不能超过 100")
        @Pattern(regexp = "[A-Za-z0-9_.:-]+", message = "岗位编码只能包含字母、数字、下划线、点、冒号与短横线")
        String positionCode,
        @NotBlank(message = "岗位名称不能为空")
        @Size(max = 100, message = "岗位名称长度不能超过 100")
        String positionName,
        @Size(max = 32, message = "职级长度不能超过 32")
        String positionLevel,
        Integer sortOrder
) {
}
