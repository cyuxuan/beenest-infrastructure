package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 岗位响应。
 *
 * @param id            岗位主键
 * @param positionCode  岗位编码
 * @param positionName  岗位名称
 * @param positionLevel 职级/序列(展示用)
 * @param sortOrder     排序号
 * @param roleCount     岗位已挂载的角色数(任职该岗位即继承)
 * @param createdTime   创建时间
 */
public record PositionResponse(
        Long id,
        String positionCode,
        String positionName,
        String positionLevel,
        Integer sortOrder,
        long roleCount,
        LocalDateTime createdTime
) {
}
