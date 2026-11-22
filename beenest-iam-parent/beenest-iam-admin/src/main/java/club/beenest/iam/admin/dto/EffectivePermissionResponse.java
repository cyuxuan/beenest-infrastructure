package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户有效权限诊断结果。
 * <p>各来源列表可能交叠,effectivePermissions 为并集、特批与拒绝结算后的结果。</p>
 *
 * @param userId                 被诊断用户主键
 * @param appServiceId           诊断的应用范围,为空表示全部命名空间
 * @param appName                应用名称,为空表示全部命名空间
 * @param roles                  生效角色及授权来源
 * @param roleDirectPermissions  角色直接授予的权限
 * @param menuDerivedPermissions 菜单授予派生的权限
 * @param userGrantedPermissions 用户特批授予的权限
 * @param userRevokedPermissions 用户特批撤销的权限
 * @param effectivePermissions   结算后的最终权限
 * @param generatedTime          生成时间
 */
public record EffectivePermissionResponse(
        Long userId,
        Long appServiceId,
        String appName,
        List<EffectiveRoleResponse> roles,
        List<PermissionBriefResponse> roleDirectPermissions,
        List<PermissionBriefResponse> menuDerivedPermissions,
        List<PermissionBriefResponse> userGrantedPermissions,
        List<PermissionBriefResponse> userRevokedPermissions,
        List<PermissionBriefResponse> effectivePermissions,
        LocalDateTime generatedTime
) {
}
