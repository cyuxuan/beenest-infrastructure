package club.beenest.iam.admin.dto;

/**
 * 角色来源中的岗位引用。
 * <p>用户因在组内任职该岗位而继承角色。</p>
 *
 * @param id           岗位主键
 * @param positionCode 岗位编码
 * @param positionName 岗位名称
 */
public record RolePositionSourceResponse(
        Long id,
        String positionCode,
        String positionName
) {
}
