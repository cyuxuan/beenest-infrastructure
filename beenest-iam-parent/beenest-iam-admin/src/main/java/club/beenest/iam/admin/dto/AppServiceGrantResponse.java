package club.beenest.iam.admin.dto;

/**
 * 应用授权记录响应。
 * <p>仅包含主体类型与主键,主体名称由对应主体的接口提供。</p>
 *
 * @param id          记录主键
 * @param appServiceId 应用服务主键
 * @param subjectType 主体类型:USER / ROLE
 * @param subjectId   主体主键
 */
public record AppServiceGrantResponse(
        Long id,
        Long appServiceId,
        String subjectType,
        Long subjectId
) {
}
