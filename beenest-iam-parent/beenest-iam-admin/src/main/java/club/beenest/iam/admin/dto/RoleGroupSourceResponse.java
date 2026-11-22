package club.beenest.iam.admin.dto;

/**
 * 角色来源中的用户组引用。
 *
 * @param id        组主键
 * @param groupCode 组编码
 * @param groupName 组名称
 */
public record RoleGroupSourceResponse(
        Long id,
        String groupCode,
        String groupName
) {
}
