package club.beenest.iam.admin.dto;

import java.util.List;

/**
 * 用户有效角色及其授权来源。
 *
 * @param id            角色主键
 * @param roleCode      角色编码
 * @param roleName      角色名称
 * @param containerType 容器类型:REALM / APP / CLIENT
 * @param direct        是否由用户直授(为 false 时仍可能通过组或岗位继承)
 * @param groups        继承该角色的用户组
 * @param positions     继承该角色的岗位(因任职而生效)
 */
public record EffectiveRoleResponse(
        Long id,
        String roleCode,
        String roleName,
        String containerType,
        boolean direct,
        List<RoleGroupSourceResponse> groups,
        List<RolePositionSourceResponse> positions
) {
}
