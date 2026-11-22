package club.beenest.iam.admin.dto;

/**
 * 权限筛选范围(应用服务)。
 *
 * @param id      应用主键
 * @param appCode 应用编码
 * @param appName 应用名称
 * @param enabled 是否启用
 */
public record PermissionScopeResponse(
        Long id,
        String appCode,
        String appName,
        Boolean enabled
) {
}
