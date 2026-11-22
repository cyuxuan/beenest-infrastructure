package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Size;

/**
 * 岗位更新请求(仅更新显式给出的字段)。
 * <p>岗位编码不可修改。</p>
 *
 * @param positionName  岗位名称
 * @param positionLevel 职级/序列
 * @param sortOrder     排序号
 */
public record PositionUpdateRequest(
        @Size(max = 100, message = "岗位名称长度不能超过 100")
        String positionName,
        @Size(max = 32, message = "职级长度不能超过 32")
        String positionLevel,
        Integer sortOrder
) {
}
