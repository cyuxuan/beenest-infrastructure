package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 可授予用户的权限候选,附带用户当前特批状态。
 *
 * @param id                 权限主键
 * @param permissionCode     权限编码
 * @param permissionName     权限名称
 * @param appServiceId       所属应用主键,为空表示平台级权限
 * @param appName            所属应用名称
 * @param appCode            所属应用编码
 * @param currentGrantType   当前特批类型:GRANT / REVOKE,无特批时为空
 * @param currentActive      当前特批是否生效,无特批时为空
 * @param currentExpiredTime 当前特批过期时间,为空表示长期有效或无特批
 */
public record PermissionCandidateResponse(
        Long id,
        String permissionCode,
        String permissionName,
        Long appServiceId,
        String appName,
        String appCode,
        String currentGrantType,
        Boolean currentActive,
        LocalDateTime currentExpiredTime
) {
}
