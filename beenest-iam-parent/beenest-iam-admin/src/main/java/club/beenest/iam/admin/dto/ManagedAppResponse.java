package club.beenest.iam.admin.dto;

/**
 * 当前用户可管理的应用摘要。
 *
 * @param id                应用主键
 * @param appCode           应用编码
 * @param appName           应用名称
 * @param authorizationMode 授权模式
 * @param tokenProfile      令牌收敛档
 * @param enabled           是否启用
 */
public record ManagedAppResponse(
        Long id,
        String appCode,
        String appName,
        String authorizationMode,
        String tokenProfile,
        Boolean enabled
) {
}
