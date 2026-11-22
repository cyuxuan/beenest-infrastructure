package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 角色-菜单关联实体(iam_role_menu)。
 * <p>授予菜单即同时派生该菜单下的全部权限。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class RoleMenu extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 角色主键 */
    private Long roleId;
    /** 菜单主键 */
    private Long menuId;
}
