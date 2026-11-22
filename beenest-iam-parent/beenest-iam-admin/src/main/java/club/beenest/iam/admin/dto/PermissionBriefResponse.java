package club.beenest.iam.admin.dto;

/**
 * 权限简要信息,用于诊断结果中的权限引用。
 *
 * @param id             权限主键
 * @param permissionCode 权限编码(resource:action)
 * @param permissionName 权限名称
 * @param appServiceId   所属应用主键,为空表示平台级权限
 * @param appName        所属应用名称
 */
public record PermissionBriefResponse(
        Long id,
        String permissionCode,
        String permissionName,
        Long appServiceId,
        String appName
) {
}
