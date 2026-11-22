package club.beenest.iam.admin.dto;

import java.util.List;

/**
 * 应用授权名单更新请求(整体覆盖式)。
 *
 * @param subjectIds 目标主体主键集合;空集合表示清空该类型授权
 */
public record AppServiceGrantUpdateRequest(
        List<Long> subjectIds
) {
}
